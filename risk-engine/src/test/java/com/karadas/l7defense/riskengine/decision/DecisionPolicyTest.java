package com.karadas.l7defense.riskengine.decision;

import com.karadas.l7defense.riskengine.scoring.AttackType;
import com.karadas.l7defense.riskengine.scoring.RiskScore;
import com.karadas.l7defense.riskengine.signal.Decision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eşik, hysteresis ve tip-mitigation eşlemesinin testi.
 *
 * <p>Hysteresis'i elle doğrulamak neredeyse imkânsız: skoru curl ile tam 120'ye
 * çıkarıp sonra tam 40'a düşürmen gerekir. Bir kez denedik ve "bu gerçekten
 * hysteresis mi, yoksa state silindi de tesadüfen mi aynı çıktı" diye ayırt
 * edemedik. Test bunu kesin olarak sabitliyor.
 */
class DecisionPolicyTest {

    // Üretimdeki değerlerle aynı: eşik 50/120, hysteresis oranı 0.5
    // (yani çıkış eşikleri 25 ve 60), ceza süresi 30 saniye.
    private static DecisionPolicy policy(Clock clock) {
        DecisionStateStore store =
                new DecisionStateStore(100, 100, Duration.ofMinutes(10));
        return new DecisionPolicy(store, clock, 50, 120, 0.5, Duration.ofSeconds(30));
    }

    private static DecisionPolicy policy() {
        return policy(Clock.systemUTC());
    }

    /** byType haritası burada kullanılmıyor — DecisionPolicy yalnızca toplama ve baskın tipe bakıyor. */
    private static RiskScore score(AttackType type, int total) {
        return new RiskScore(Map.of(), type, total, 0, total);
    }

    // ------------------------------------------------------------- hysteresis

    @Test
    void sameScoreGivesDifferentResultDependingOnWhereYouCameFrom() {
        // Bu testin tek başına anlatmak istediği şey: 40 puanın anlamı, o puana
        // hangi yönden gelindiğine bağlı. Sistemdeki ilk gerçek durum makinesi.

        // Aşağıdan geliyorsa: 40 < 50, ceza yok.
        assertEquals(Severity.NONE,
                policy().decide("AUTH:1", score(AttackType.VOLUMETRIC, 40)).severity());

        // Yukarıdan geliyorsa: önce 60 ile MODERATE'e çıkıyor, sonra 40'a düşüyor
        // ama çıkış eşiği 25 olduğu için MODERATE'te kalıyor.
        DecisionPolicy p = policy();
        p.decide("AUTH:2", score(AttackType.VOLUMETRIC, 60));
        assertEquals(Severity.MODERATE,
                p.decide("AUTH:2", score(AttackType.VOLUMETRIC, 40)).severity());
    }

    @Test
    void fallingBelowTheExitThresholdClearsTheLevel() {
        DecisionPolicy p = policy();
        p.decide("AUTH:1", score(AttackType.VOLUMETRIC, 60));
        // 20 < 25 (moderate çıkış eşiği) → artık temiz
        assertEquals(Severity.NONE,
                p.decide("AUTH:1", score(AttackType.VOLUMETRIC, 20)).severity());
    }

    @Test
    void severeDropsToModerateNotStraightToNone() {
        // Merdivenden inerken de basamak atlanmıyor. 50 puan, severe çıkış
        // eşiğinin (120 × 0.5 = 60) altında ama moderate çıkış eşiğinin
        // (50 × 0.5 = 25) üstünde — yani bir basamak iniyor, sıfırlanmıyor.
        DecisionPolicy p = policy();
        p.decide("AUTH:1", score(AttackType.VOLUMETRIC, 130));
        assertEquals(Severity.MODERATE,
                p.decide("AUTH:1", score(AttackType.VOLUMETRIC, 50)).severity());
    }

    @Test
    void levelsAreTrackedPerIdentity() {
        // Bir kimliğin geçmişi başka bir kimliği etkilemiyor.
        DecisionPolicy p = policy();
        p.decide("AUTH:1", score(AttackType.VOLUMETRIC, 60));
        assertEquals(Severity.NONE,
                p.decide("AUTH:2", score(AttackType.VOLUMETRIC, 40)).severity());
    }

    // ------------------------------------------------- tip → mitigation tablosu

    @Test
    void credentialAttackEscalatesToTarpitNotDrop() {
        assertEquals(Decision.RATE_LIMIT,
                policy().decide("A", score(AttackType.CREDENTIAL_ATTACK, 60)).decision());
        assertEquals(Decision.TARPIT,
                policy().decide("B", score(AttackType.CREDENTIAL_ATTACK, 130)).decision());
    }

    @Test
    void volumetricEscalatesToDropBecauseTarpittingWouldCostUsResources() {
        assertEquals(Decision.RATE_LIMIT,
                policy().decide("A", score(AttackType.VOLUMETRIC, 60)).decision());
        assertEquals(Decision.DROP,
                policy().decide("B", score(AttackType.VOLUMETRIC, 130)).decision());
    }

    @Test
    void resourceExhaustionHasALadderToo() {
        // 4.5.3'ten önce her iki seviyede de DROP dönüyordu; severity ekseni bu
        // tip için hiçbir iş yapmıyordu.
        assertEquals(Decision.RATE_LIMIT,
                policy().decide("A", score(AttackType.RESOURCE_EXHAUSTION, 60)).decision());
        assertEquals(Decision.DROP,
                policy().decide("B", score(AttackType.RESOURCE_EXHAUSTION, 130)).decision());
    }

    // --------------------------------------------------------- kenar durumlar

    @Test
    void persistenceWithoutASurvivingTypeIsTreatedAsVolumetric() {
        // 4.13.2: pencere uzunluğundan uzun süre ceza altında kalan bir kimliğin
        // tipli kanıtları düşer, geriye yalnızca ısrar kalır. Eski kod burada
        // ALLOW dönüyordu — yani yeterince uzun cezalandırılan serbest kalıyordu.
        RiskScore onlyPersistence = new RiskScore(Map.of(), null, 0, 130, 130);
        assertEquals(Decision.DROP,
                policy().decide("AUTH:1", onlyPersistence).decision());
    }

    @Test
    void allowIsNotPublished() {
        RiskDecision d = policy().decide("AUTH:1", score(AttackType.VOLUMETRIC, 10));
        assertEquals(Decision.ALLOW, d.decision());
        assertFalse(d.shouldPublish());
    }

    @Test
    void punishmentsArePublished() {
        assertTrue(policy().decide("AUTH:1", score(AttackType.VOLUMETRIC, 60)).shouldPublish());
    }

    @Test
    void validUntilIsTheInjectedClockPlusTheConfiguredTtl() {
        // Clock enjeksiyonunun karşılığını aldığımız yer: gerçek zaman
        // beklemeden, saniyesi saniyesine doğrulanabiliyor.
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        DecisionPolicy p = policy(Clock.fixed(now, ZoneOffset.UTC));

        assertEquals(now.plusSeconds(30),
                p.decide("AUTH:1", score(AttackType.VOLUMETRIC, 60)).validUntil());
    }
}