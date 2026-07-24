package com.karadas.l7defense.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RateLimiterRegistry {

    // Same pattern as DecisionCache: one Caffeine cache, key = identity, this time
    // holding a live Bucket instead of a decision.
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .build();

    public Bucket resolveBucket(String identity) {
        return buckets.get(identity, key -> newBucket());
    }

    private Bucket newBucket() {
        // Demo-friendly starting point: 5 requests, refilling 1 every 10 seconds.
        // Easy to exhaust quickly with a few curl calls, easy to explain live.
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(5).refillGreedy(1, Duration.ofSeconds(10)))
                .build();
    }
}