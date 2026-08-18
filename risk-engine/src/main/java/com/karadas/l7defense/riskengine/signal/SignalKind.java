package com.karadas.l7defense.riskengine.signal;

/**
 * What a signal means for scoring. Classification happens once, on arrival, so the
 * window stores a small enum rather than the whole event (Design Decisions Log 4.3).
 */
public enum SignalKind {

    LOGIN_FAILURE,
    BASELINE_THROTTLE,
    UNAUTHENTICATED,
    SERVER_ERROR,
    IGNORED;

    public static SignalKind of(SignalEvent signal) {
        Decision applied = signal.mitigationApplied();

        // Our own mitigation echoing back. Scoring these would close the feedback
        // loop on itself and make de-escalation unreachable (Log 3.1.1).
        if (applied == Decision.RATE_LIMIT
                || applied == Decision.TARPIT
                || applied == Decision.DROP) {
            return IGNORED;
        }

        // Not a verdict we issued — independent evidence of exceeding the rate.
        if (applied == Decision.BASELINE_THROTTLE) {
            return BASELINE_THROTTLE;
        }
        if (applied == Decision.UNAUTHENTICATED) {
            return UNAUTHENTICATED;
        }

        Integer status = signal.status();
        if (status == null) {
            return IGNORED;
        }
        if (status == 401) {
            return "/auth/login".equals(signal.path()) ? LOGIN_FAILURE : UNAUTHENTICATED;
        }
        if (status >= 500) {
            return SERVER_ERROR;
        }
        return IGNORED;
    }
}