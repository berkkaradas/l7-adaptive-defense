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

    public ScoringService(WindowStore windowStore,
                          @Value("${app.scoring.weights.login-failure}") int loginFailureWeight,
                          @Value("${app.scoring.weights.baseline-throttle}") int baselineThrottleWeight,
                          @Value("${app.scoring.weights.unauthenticated}") int unauthenticatedWeight) {
        this.windowStore = windowStore;
        this.loginFailureWeight = loginFailureWeight;
        this.baselineThrottleWeight = baselineThrottleWeight;
        this.unauthenticatedWeight = unauthenticatedWeight;
    }

    public RiskScore scoreOf(String identity) {
        Map<SignalKind, Integer> counts = windowStore.countsFor(identity);

        Map<AttackType, Integer> byType = new EnumMap<>(AttackType.class);

        // Repeated failures against the login endpoint. Volume may be low — S2 is
        // deliberately slow — so what matters is the target, not the rate.
        byType.put(AttackType.CREDENTIAL_ATTACK,
                count(counts, SignalKind.LOGIN_FAILURE) * loginFailureWeight);

        // Bucket exhaustion is direct proof of exceeding the configured rate; a burst
        // of token-less requests is volumetric abuse whatever the intent behind it.
        byType.put(AttackType.VOLUMETRIC,
                count(counts, SignalKind.BASELINE_THROTTLE) * baselineThrottleWeight
                        + count(counts, SignalKind.UNAUTHENTICATED) * unauthenticatedWeight);

        // Filled in once the attribution test of 4.7 exists — scoring 5xx without it
        // would punish every identity active during a database wobble.
        byType.put(AttackType.RESOURCE_EXHAUSTION, 0);

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
        return new RiskScore(Map.copyOf(byType), dominant, best, total);
    }

    private static int count(Map<SignalKind, Integer> counts, SignalKind kind) {
        return counts.getOrDefault(kind, 0);
    }
}