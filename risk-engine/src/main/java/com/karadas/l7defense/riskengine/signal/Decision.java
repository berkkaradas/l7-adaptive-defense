package com.karadas.l7defense.riskengine.signal;

/**
 * Mirrors the Gateway's Decision enum. Deliberately duplicated rather than shared —
 * see Design Decisions Log 6.6 for the same choice made for JwtVerifier.
 *
 * <p>BASELINE_THROTTLE and UNAUTHENTICATED are observable outcomes, never verdicts:
 * the Risk Engine reads them but must never publish them.
 */
public enum Decision {
    ALLOW,
    BASELINE_THROTTLE,
    UNAUTHENTICATED,
    RATE_LIMIT,
    TARPIT,
    DROP
}
