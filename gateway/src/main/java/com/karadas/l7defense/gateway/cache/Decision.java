package com.karadas.l7defense.gateway.cache;

public enum Decision {
    ALLOW,
    BASELINE_THROTTLE,
    UNAUTHENTICATED,
    RATE_LIMIT,
    TARPIT,
    DROP
}