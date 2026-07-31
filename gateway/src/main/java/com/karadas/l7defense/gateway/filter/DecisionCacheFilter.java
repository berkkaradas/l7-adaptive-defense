package com.karadas.l7defense.gateway.filter;

import com.karadas.l7defense.gateway.cache.CachedDecision;
import com.karadas.l7defense.gateway.cache.Decision;
import com.karadas.l7defense.gateway.cache.DecisionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Component
public class DecisionCacheFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DecisionCacheFilter.class);
    private static final String IDENTITY_HEADER = "X-Resolved-Identity";

    // Exchange attribute -- internal to the Gateway's own filter chain only, never sent
    // to a backend (unlike the identity header, which does cross that boundary).
    public static final String RESOLVED_DECISION_ATTR = "resolvedDecision";

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
        Decision decision = (cached.isPresent() && cached.get().isActive())
                ? cached.get().decision()
                : Decision.ALLOW;

        log.info("Resolved decision={} for identity={}", decision, identity);
        exchange.getAttributes().put(RESOLVED_DECISION_ATTR, decision);
        return chain.filter(exchange);
    }
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}