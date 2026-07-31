package com.karadas.l7defense.gateway.filter;

import com.karadas.l7defense.gateway.cache.Decision;
import com.karadas.l7defense.gateway.signal.SignalEvent;
import com.karadas.l7defense.gateway.signal.SignalPublisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Emits one signal per request outcome, after the response has been produced.
 *
 * <p>Purely observational — it wraps the entire chain but never alters the outcome.
 * It must run outermost so that requests short-circuited by mitigation (RATE_LIMIT,
 * DROP) are still reported: those paths never call chain.filter(), so a filter placed
 * further in would silently miss exactly the requests that matter most.
 */
@Component
public class SignalEmissionFilter implements GlobalFilter, Ordered {

    private static final String SOURCE = "gateway";

    private final SignalPublisher publisher;

    public SignalEmissionFilter(SignalPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startNanos = System.nanoTime();
        return chain.filter(exchange)
                .doFinally(signalType -> emit(exchange, startNanos));
    }

    private void emit(ServerWebExchange exchange, long startNanos) {
        String identity = (String) exchange.getAttributes()
                .get(IdentityResolutionFilter.RESOLVED_IDENTITY_ATTR);
        if (identity == null) {
            // No identity was resolved, so this was rejected with 401 before the pipeline
            // could attribute it to anyone. Decided not to score these.
            return;
        }

        Decision applied = (Decision) exchange.getAttributes()
                .getOrDefault(MitigationEnforcementFilter.APPLIED_MITIGATION_ATTR, Decision.ALLOW);

        long tarpitMs = (long) exchange.getAttributes()
                .getOrDefault(MitigationEnforcementFilter.TARPIT_DELAY_MS_ATTR, 0L);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        long latencyMs = Math.max(0L, elapsedMs - tarpitMs);

        publisher.publish(new SignalEvent(
                identity,
                Instant.now(),
                resolveClientIp(exchange),
                exchange.getRequest().getPath().value(),
                resolveStatus(exchange, applied),
                latencyMs,
                applied,
                SOURCE
        ));
    }

    /**
     * On the DROP path the connection is disposed without ever writing a response, so
     * there genuinely is no HTTP status. Reactor Netty would report 200 here — its
     * getStatusCode() falls back to the native response's default when none was set —
     * which would tell the Risk Engine the request succeeded.
     */
    private Integer resolveStatus(ServerWebExchange exchange, Decision applied) {
        if (applied == Decision.DROP) {
            return null;
        }
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        return status != null ? status.value() : null;
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        return exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}