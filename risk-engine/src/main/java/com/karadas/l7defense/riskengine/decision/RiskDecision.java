package com.karadas.l7defense.riskengine.decision;

import com.karadas.l7defense.riskengine.scoring.AttackType;
import com.karadas.l7defense.riskengine.signal.Decision;

import java.time.Instant;

/**
 * Risk Engine'in bir kimlik hakkında verdiği karar.
 *
 * <p>Skoru ve saldırı tipini de taşıyor. Gateway bunları kullanmıyor — ona
 * yalnızca decision ve validUntil lazım — ama precision/recall analizi her
 * kararı gerekçesiyle birlikte görmek zorunda (Karar Kaydı 4.3).
 *
 * @param validUntil cezanın bittiği an. Kısa tutulup her sinyalde yenileniyor,
 *                   böylece saldırgan durduğunda ceza kendiliğinden sona eriyor.
 */
public record RiskDecision(
        String identity,
        Decision decision,
        AttackType attackType,
        Severity severity,
        int score,
        Instant validUntil
) {
    /**
     * ALLOW kararı yayınlanmıyor. Ceza yenilenmediğinde zaten süresi dolup
     * cache'ten düşüyor, yani "serbest bırak" mesajı göndermeye gerek yok.
     */
    public boolean shouldPublish() {
        return decision != Decision.ALLOW;
    }
}