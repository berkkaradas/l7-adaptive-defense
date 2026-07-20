package com.karadas.l7defense.gateway.filter;

import com.karadas.l7defense.gateway.cache.CachedDecision;
import com.karadas.l7defense.gateway.cache.Decision;
import com.karadas.l7defense.gateway.cache.DecisionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Component
public class DecisionCacheFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DecisionCacheFilter.class);
    private static final String IDENTITY_HEADER = "X-Resolved-Identity";

    private final DecisionCache decisionCache;

    public DecisionCacheFilter(DecisionCache decisionCache) {
        this.decisionCache = decisionCache;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // IdentityResolutionFilter runs first (lower order) and always either attaches
        // this header or rejects the request outright -- it is never null here.
        String identity = exchange.getRequest().getHeaders().getFirst(IDENTITY_HEADER);

        Optional<CachedDecision> cached = decisionCache.get(identity);
        if (cached.isEmpty() || !cached.get().isActive()) {
            log.info("No active decision for identity={}, allowing", identity);
            return chain.filter(exchange);
        }

        Decision decision = cached.get().decision();
        if (decision == Decision.DROP) {
            log.warn("Blocking identity={} (decision=DROP, validUntil={})", identity, cached.get().validUntil());
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        // RATE_LIMIT / TARPIT are resolved here but not yet enforced -- that mechanism
        // is a separate, not-yet-built filter (the "static rate limiter baseline").
        return chain.filter(exchange);
    }

    // One step after IdentityResolutionFilter (HIGHEST_PRECEDENCE) -- runs second, not first.
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}