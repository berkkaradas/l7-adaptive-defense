package com.karadas.l7defense.gateway.cache;

import java.time.Instant;

public record CachedDecision(Decision decision, Instant validUntil) {

    public boolean isActive() {
        return Instant.now().isBefore(validUntil);
    }
}