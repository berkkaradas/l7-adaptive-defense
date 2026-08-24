package com.karadas.l7defense.riskengine.decision;

import com.karadas.l7defense.riskengine.scoring.AttackType;
import com.karadas.l7defense.riskengine.signal.Decision;

import java.time.Instant;

/**
 * l7.decisions topic'inde taşınan mesaj — Risk Engine ile Gateway arasındaki
 * kontrat (Karar Kaydı 4.12.1).
 *
 * <p>RiskDecision'ı doğrudan yayınlamıyoruz. RiskDecision iç modelimiz; bu ise
 * dışarıya verdiğimiz söz. İkisini ayırmak, iç modeli değiştirdiğimizde tel
 * üzerindeki formatı kazara bozmamamızı sağlıyor.
 *
 * <p>Gateway'in ihtiyacı yalnızca identity, decision ve validUntil. Geri kalanı
 * kanıt: deney sonrası precision/recall hesabı "hangi kararı verdik" değil
 * "neden verdik" sorusunu da sormak zorunda (4.3).
 *
 * @param issuedAt kararın üretildiği an. validUntil'den türetilebilir ama açıkça
 *                 taşıyoruz — TTD'nin iki ucundan biri bu (11.2), ve log
 *                 ayrıştırmak yerine topic'ten okunabilmesi gerekiyor.
 * @param source   hangi bileşen üretti. Şimdilik tek üretici var; alan, ileride
 *                 ikincisi çıkarsa geriye dönük uyumluluk için (3.1.2 ile aynı
 *                 gerekçe).
 */
public record DecisionEvent(
        String identity,
        Decision decision,
        Instant validUntil,
        AttackType attackType,
        Severity severity,
        int score,
        Instant issuedAt,
        String source
) {
    private static final String SOURCE = "risk-engine";

    public static DecisionEvent from(RiskDecision decision, Instant issuedAt) {
        return new DecisionEvent(
                decision.identity(),
                decision.decision(),
                decision.validUntil(),
                decision.attackType(),
                decision.severity(),
                decision.score(),
                issuedAt,
                SOURCE);
    }
}