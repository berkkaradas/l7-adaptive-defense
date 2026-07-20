package com.karadas.l7defense.gateway.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class DecisionCache {

    private final Cache<String, CachedDecision> cache;

    public DecisionCache() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(30))
                .maximumSize(50_000)
                .build();
    }

    public Optional<CachedDecision> get(String identity) {
        return Optional.ofNullable(cache.getIfPresent(identity));
    }

    public void put(String identity, CachedDecision decision) {
        cache.put(identity, decision);
    }
}