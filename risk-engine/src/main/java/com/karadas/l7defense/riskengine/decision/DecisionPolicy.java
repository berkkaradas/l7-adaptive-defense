package com.karadas.l7defense.riskengine.decision;

import com.karadas.l7defense.riskengine.scoring.AttackType;
import com.karadas.l7defense.riskengine.scoring.RiskScore;
import com.karadas.l7defense.riskengine.signal.Decision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Skoru karara çevirir: eşik, hysteresis, tip-mitigation eşlemesi ve süre.
 *
 * <p>Bütün eşik mantığı tek yerde toplandı; testlerin hedefi bu sınıf.
 */
@Component
public class DecisionPolicy {

    private final DecisionStateStore stateStore;
    private final Clock clock;
    private final int moderateThreshold;
    private final int severeThreshold;
    private final double hysteresisRatio;
    private final Duration decisionTtl;

    public DecisionPolicy(DecisionStateStore stateStore,
                          Clock clock,
                          @Value("${app.decision.threshold.moderate}") int moderateThreshold,
                          @Value("${app.decision.threshold.severe}") int severeThreshold,
                          @Value("${app.decision.hysteresis-ratio}") double hysteresisRatio,
                          @Value("${app.decision.ttl}") Duration decisionTtl) {
        this.stateStore = stateStore;
        this.clock = clock;
        this.moderateThreshold = moderateThreshold;
        this.severeThreshold = severeThreshold;
        this.hysteresisRatio = hysteresisRatio;
        this.decisionTtl = decisionTtl;
    }

    public RiskDecision decide(String identity, RiskScore score) {
        Severity previous = stateStore.currentLevel(identity);
        Severity next = nextSeverity(score.totalScore(), previous);
        stateStore.update(identity, next);

        Decision mitigation = mitigationFor(score.dominantType(), next);

        // Süre kısa ve her sinyalde yenileniyor. Saldırgan durduğunda yeni karar
        // üretilmediği için ceza kendiliğinden sona eriyor — ayrı bir "serbest
        // bırak" mekanizmasına gerek kalmıyor.
        Instant validUntil = clock.instant().plus(decisionTtl);

        return new RiskDecision(identity, mitigation, score.dominantType(),
                next, score.totalScore(), validUntil);
    }

    /**
     * Hysteresis burada. Yükselmek için tam eşiği geçmek gerekiyor, düşmek için
     * eşiğin belirli bir oranının altına inmek. Aradaki boşlukta hiçbir şey
     * değişmiyor — hangi yönden gelmiş olursan ol.
     *
     * <p>Olmasaydı eşiğin dibinde gezinen bir kimlik sürekli açılıp kapanırdı;
     * kullanıcı sistemi rastgele bozuk görür, decisions topic'i çelişkili
     * kararlarla dolar ve en önemlisi TTD tanımsız hale gelirdi — mitigation
     * beş kez tetiklendiyse t_mitigation hangisidir?
     */
    private Severity nextSeverity(int score, Severity previous) {
        int moderateExit = (int) (moderateThreshold * hysteresisRatio);
        int severeExit = (int) (severeThreshold * hysteresisRatio);

        return switch (previous) {
            // Temizken yükselmek için tam eşik gerekiyor.
            case NONE -> {
                if (score >= severeThreshold) yield Severity.SEVERE;
                if (score >= moderateThreshold) yield Severity.MODERATE;
                yield Severity.NONE;
            }
            // MODERATE'teyken yukarı çıkmak için SEVERE eşiği; aşağı inmek için
            // MODERATE'in çıkış eşiğinin altına düşmek.
            case MODERATE -> {
                if (score >= severeThreshold) yield Severity.SEVERE;
                if (score < moderateExit) yield Severity.NONE;
                yield Severity.MODERATE;
            }
            // SEVERE'deyken tek adımda NONE'a düşmek mümkün ama ancak skor
            // MODERATE'in çıkış eşiğinin de altına inerse.
            case SEVERE -> {
                if (score < moderateExit) yield Severity.NONE;
                if (score < severeExit) yield Severity.MODERATE;
                yield Severity.SEVERE;
            }
        };
    }

    /**
     * Tip tepkinin TÜRÜNÜ, şiddet SEVİYESİNİ belirliyor (Karar Kaydı 4.5).
     * Böylece merdiven korunuyor ama tepki tehdide uygun oluyor.
     */
    private Decision mitigationFor(AttackType type, Severity severity) {
        if (severity == Severity.NONE) {
            return Decision.ALLOW;
        }

        // Pencere uzunluğundan (180 sn) daha uzun süre ceza altında kalan bir
        // kimliğin tipli kanıtlarının tamamı zaman aşımına uğrayabilir; geriye
        // yalnızca ısrar puanı kalır ve baskın tip null olur. Bu durumda
        // gözlenebilen tek davranış "engellenmesine rağmen istek atmaya devam
        // etmek"tir, ki bu tanımı gereği hacimsel davranıştır.
        //
        // Eski kod burada ALLOW dönüyordu: yani bir kimlik, YETERİNCE UZUN
        // cezalandırıldığı için serbest kalıyordu. 4.13'ün ortadan kaldırmak
        // için var olduğu hata tam olarak buydu (4.13.2).
        AttackType effective = (type == null) ? AttackType.VOLUMETRIC : type;

        return switch (effective) {
            // Brute force'u yavaşlatmak denemeyi ekonomik olmaktan çıkarıyor;
            // sadece hız kesmek saldırganı durdurmuyor, sadece rahatsız ediyor.
            case CREDENTIAL_ATTACK -> severity == Severity.SEVERE
                    ? Decision.TARPIT
                    : Decision.RATE_LIMIT;

            // Hacim saldırısında yavaşlatmanın faydası yok; hacmi kesmek gerekiyor.
            case VOLUMETRIC -> severity == Severity.SEVERE
                    ? Decision.DROP
                    : Decision.RATE_LIMIT;

            // Diğer tiplerde saldırgan kendi kaynağını harcıyor, burada
            // başkasınınkini. Tolerans bandı bu yüzden daha dar: düşük şiddette
            // bile doğrudan DROP.
            case RESOURCE_EXHAUSTION -> severity == Severity.SEVERE
                    ? Decision.DROP
                    : Decision.RATE_LIMIT;
        };
    }
}