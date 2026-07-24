package com.karadas.l7defense.gateway.filter;

import com.karadas.l7defense.gateway.cache.Decision;
import com.karadas.l7defense.gateway.ratelimit.RateLimiterRegistry;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.http.server.HttpServerResponse;

import java.time.Duration;

@Component
public class MitigationEnforcementFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MitigationEnforcementFilter.class);
    private static final String IDENTITY_HEADER = "X-Resolved-Identity";
    private static final Duration TARPIT_DELAY = Duration.ofSeconds(3);

    private final RateLimiterRegistry rateLimiterRegistry;

    public MitigationEnforcementFilter(RateLimiterRegistry rateLimiterRegistry) {
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Decision decision = (Decision) exchange.getAttributes()
                .getOrDefault(DecisionCacheFilter.RESOLVED_DECISION_ATTR, Decision.ALLOW);
        String identity = exchange.getRequest().getHeaders().getFirst(IDENTITY_HEADER);

        if (decision == Decision.RATE_LIMIT) {
            log.warn("identity={} already flagged RATE_LIMIT by Risk Engine -- rejecting directly, no bucket check", identity);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }
        if (decision == Decision.TARPIT) {
            return enforceTarpit(exchange, chain, identity);
        }
        if (decision == Decision.DROP) {
            return enforceSilentDrop(exchange, identity);
        }

        // decision == ALLOW -- still subject to the baseline bucket, applied to everyone
        // regardless of Risk Engine's verdict. This is where a normal member's tokens
        // drain one by one, and running out is what naturally produces a 429.
        return enforceBaselineRateLimit(exchange, chain, identity);
    }

    private Mono<Void> enforceBaselineRateLimit(ServerWebExchange exchange, GatewayFilterChain chain, String identity) {
        Bucket bucket = rateLimiterRegistry.resolveBucket(identity);
        if (bucket.tryConsume(1)) {
            return chain.filter(exchange);
        }
        log.warn("Baseline rate limit exceeded for identity={}", identity);
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        return exchange.getResponse().setComplete();
    }

    private Mono<Void> enforceTarpit(ServerWebExchange exchange, GatewayFilterChain chain, String identity) {
        log.info("Tarpitting identity={} for {}", identity, TARPIT_DELAY);
        return Mono.delay(TARPIT_DELAY).then(chain.filter(exchange));
    }

    private Mono<Void> enforceSilentDrop(ServerWebExchange exchange, String identity) {
        log.warn("Silently dropping connection for identity={}", identity);
        if (exchange.getResponse() instanceof AbstractServerHttpResponse abstractResponse
                && abstractResponse.getNativeResponse() instanceof HttpServerResponse nettyResponse) {
            nettyResponse.withConnection(Connection::dispose);
            return Mono.empty();
        }
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}