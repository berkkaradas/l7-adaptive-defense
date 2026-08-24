package com.karadas.l7defense.riskengine.scoring;

import com.karadas.l7defense.riskengine.signal.SignalKind;
import com.karadas.l7defense.riskengine.window.WindowStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Turns a window of events into a score per attack type.
 *
 * <p>Weights are assumptions rather than measurements — they encode a threat model
 * and no data justifies them. They live in configuration precisely so the
 * sensitivity analysis can vary them (Design Decisions Log 4.4).
 */
@Component
public class ScoringService {

    private final WindowStore windowStore;
    private final int loginFailureWeight;
    private final int baselineThrottleWeight;
    private final int unauthenticatedWeight;
    private final int serverErrorWeight;
    private final int systemWideErrorThreshold;
    private final double concentrationThreshold;
    private final int persistenceWeight;


    public ScoringService(WindowStore windowStore,
                          @Value("${app.scoring.weights.login-failure}") int loginFailureWeight,
                          @Value("${app.scoring.weights.baseline-throttle}") int baselineThrottleWeight,
                          @Value("${app.scoring.weights.unauthenticated}") int unauthenticatedWeight,
                            @Value("${app.scoring.weights.server-error}") int serverErrorWeight,
                            @Value("${app.scoring.attribution.system-wide-error-threshold}") int systemWideErrorThreshold,
                            @Value("${app.scoring.attribution.concentration-threshold}") double concentrationThreshold,
                            @Value("${app.scoring.weights.mitigated-retry}") int persistenceWeight
                            ){
        this.windowStore = windowStore;
        this.loginFailureWeight = loginFailureWeight;
        this.baselineThrottleWeight = baselineThrottleWeight;
        this.unauthenticatedWeight = unauthenticatedWeight;
        this.systemWideErrorThreshold = systemWideErrorThreshold;
        this.concentrationThreshold = concentrationThreshold;
        this.serverErrorWeight = serverErrorWeight;
        this.persistenceWeight = persistenceWeight;
    }

    public RiskScore scoreOf(String identity) {
        Map<SignalKind, Integer> identityCounts = windowStore.countsFor(identity);

        Map<AttackType, Integer> byType = new EnumMap<>(AttackType.class);

        // Repeated failures against the login endpoint. Volume may be low — S2 is
        // deliberately slow — so what matters is the target, not the rate.
        byType.put(AttackType.CREDENTIAL_ATTACK,
                count(identityCounts, SignalKind.LOGIN_FAILURE) * loginFailureWeight);

        // Bucket exhaustion is direct proof of exceeding the configured rate; a burst
        // of token-less requests is volumetric abuse whatever the intent behind it.
        byType.put(AttackType.VOLUMETRIC,
                count(identityCounts, SignalKind.BASELINE_THROTTLE) * baselineThrottleWeight
                        + count(identityCounts, SignalKind.UNAUTHENTICATED) * unauthenticatedWeight);

        // Filled in once the attribution test of 4.7 exists — scoring 5xx without it
        // would punish every identity active during a database wobble.
        byType.put(AttackType.RESOURCE_EXHAUSTION,
                resourceExhaustionScore(identityCounts));

        AttackType dominant = null;
        int total = 0;
        int best = 0;
        for (AttackType type : AttackType.values()) {   // declaration order breaks ties
            int score = byType.get(type);
            total += score;
            if (score > best) {
                best = score;
                dominant = type;
            }
        }

        // Israr, hiçbir saldırı tipine yazılmıyor. 429'a rağmen devam etmek bir
        // saldırı TÜRÜ değil, verilen cezaya karşı gösterilen bir DAVRANIŞ.
        // Toplama eklenip şiddeti yükseltiyor, ama baskın tipi değiştirmediği
        // için hangi cezanın verileceğini bozmuyor — 4.5'in "tip türü, şiddet
        // seviyeyi belirler" kuralının doğrudan devamı (4.13).
        int persistence = count(identityCounts, SignalKind.MITIGATED_RETRY) * persistenceWeight;
        total += persistence;

        return new RiskScore(Map.copyOf(byType), dominant, best, persistence, total);
    }

    private static int count(Map<SignalKind, Integer> counts, SignalKind kind) {
        return counts.getOrDefault(kind, 0);
    }
    /**
     * Scores server errors only when this identity is plausibly their cause.
     *
     * <p>A 5xx describes the system's condition, not the caller's conduct. Scoring
     * every error would mean that one database wobble punishes every identity active
     * at that moment — the defence amplifying an incident instead of absorbing it.
     * Ignoring them entirely would let an attacker who found an expensive query walk
     * away from the failures they caused. So we attribute rather than choose (4.7).
     */
    private int resourceExhaustionScore(Map<SignalKind, Integer> identityCounts) {
        int identityErrors = count(identityCounts, SignalKind.SERVER_ERROR);
        if (identityErrors == 0) {
            return 0;
        }

        int systemErrors = windowStore.globalErrorCount();

        // Case 1 — nothing widespread is happening, so these errors belong to whoever
        // triggered them.
        if (systemErrors < systemWideErrorThreshold) {
            return identityErrors * serverErrorWeight;
        }

        // Case 2 — something widespread is happening, but most of it comes from this
        // identity. Without this branch an attacker raises the system-wide rate
        // themselves and then hides inside the noise they created.
        double share = (double) identityErrors / systemErrors;
        if (share >= concentrationThreshold) {
            return identityErrors * serverErrorWeight;
        }

        // Case 3 — widespread and spread out. A system condition; nobody is charged.
        return 0;
    }
}