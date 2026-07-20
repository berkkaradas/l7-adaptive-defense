package com.karadas.l7defense.gateway.cache;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionCacheTest {

    @Test
    void missingIdentityReturnsEmpty() {
        DecisionCache cache = new DecisionCache();
        assertTrue(cache.get("AUTH:999").isEmpty());
    }

    @Test
    void putThenGetReturnsSameDecision() {
        DecisionCache cache = new DecisionCache();
        Instant future = Instant.now().plus(5, ChronoUnit.MINUTES);
        cache.put("AUTH:5", new CachedDecision(Decision.DROP, future));

        Optional<CachedDecision> result = cache.get("AUTH:5");
        assertTrue(result.isPresent());
        assertEquals(Decision.DROP, result.get().decision());
        assertTrue(result.get().isActive());
    }

    @Test
    void expiredValidUntilIsNotActive() {
        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
        CachedDecision expired = new CachedDecision(Decision.DROP, past);
        assertFalse(expired.isActive());
    }
}