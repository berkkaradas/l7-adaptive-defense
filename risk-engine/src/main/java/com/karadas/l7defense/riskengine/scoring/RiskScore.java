package com.karadas.l7defense.riskengine.scoring;

import java.util.Map;

/**
 * The outcome of scoring one identity: a score per attack type, plus whichever
 * type currently dominates.
 *
 * @param byType           score for every type, including zeros — kept so a decision
 *                         can be explained afterwards (Design Decisions Log 4.3)
 * @param dominantType     the highest-scoring type, or null when nothing scored
 * @param dominantScore    the highest score, 0 when there is no evidence
 * @param persistenceScore ceza altındayken atılan isteklerin puanı. Hiçbir
 *                         AttackType'a ait değil — ısrar bir saldırı türü değil,
 *                         bir davranış (4.13). Yalnızca toplama ekleniyor.
 * @param totalScore       byType toplamı + persistenceScore. Eşik bununla
 *                         karşılaştırılıyor.
 */
public record RiskScore(
        Map<AttackType, Integer> byType,
        AttackType dominantType,
        int dominantScore,
        int persistenceScore,
        int totalScore
) {
    public boolean hasEvidence() {
        return totalScore > 0;
    }
}