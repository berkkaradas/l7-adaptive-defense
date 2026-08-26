package com.karadas.l7defense.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Kimlik başına bir token bucket. DecisionCache ile aynı desen: tek Caffeine
 * cache, anahtar kimlik, değer bu sefer karar değil canlı bir Bucket.
 *
 * <p>Ayarlar config'ten geliyor, sabit kodlu değil. Bunun iki sebebi var.
 * Birincisi demo ile deneyin farklı ayar istemesi: elle göstermek için kolay
 * taşan bir kova lazım, deneyde ise meşru kullanıcı asla takılmamalı. İkincisi
 * daha önemli — bu değerler baseline koşulunun TEK parametresi (11.3), yani
 * "statik limiter'ı adil mi ayarladınız" sorusunun cevabı burada. Yeniden
 * derlemeden değiştirilebilmesi, o soruya ölçümle cevap verebilmek demek.
 */
@Component
public class RateLimiterRegistry {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .build();

    private final long capacity;
    private final long refillTokens;
    private final Duration refillPeriod;

    public RateLimiterRegistry(
            @Value("${app.ratelimit.capacity}") long capacity,
            @Value("${app.ratelimit.refill-tokens}") long refillTokens,
            @Value("${app.ratelimit.refill-period}") Duration refillPeriod) {
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriod = refillPeriod;
    }

    public Bucket resolveBucket(String identity) {
        return buckets.get(identity, key -> newBucket());
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity)
                        .refillGreedy(refillTokens, refillPeriod))
                .build();
    }
}