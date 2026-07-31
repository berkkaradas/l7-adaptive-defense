package com.karadas.l7defense.gateway.cache;

public enum Decision {
    ALLOW,
    BASELINE_THROTTLE,
    RATE_LIMIT,
    TARPIT,
    DROP
}