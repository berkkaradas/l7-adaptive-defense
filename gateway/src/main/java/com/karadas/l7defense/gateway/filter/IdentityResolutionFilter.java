package com.karadas.l7defense.gateway.filter;

import tools.jackson.databind.ObjectMapper;
import com.karadas.l7defense.gateway.security.JwtVerifier;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

@Component
public class IdentityResolutionFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdentityResolutionFilter.class);
    private static final String IDENTITY_HEADER = "X-Resolved-Identity";
    private static final int MAX_USERNAME_LENGTH = 254;

    private final JwtVerifier jwtVerifier;
    private final ObjectMapper objectMapper;

    public IdentityResolutionFilter(JwtVerifier jwtVerifier, ObjectMapper objectMapper) {
        this.jwtVerifier = jwtVerifier;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        Optional<Claims> claims = extractValidClaims(request);
        if (claims.isPresent()) {
            Long memberId = claims.get().get("memberId", Long.class);
            return forward(exchange, chain, "AUTH:" + memberId);
        }

        if (isPublicUnauthenticated(request)) {
            String ip = resolveClientIp(exchange);
            return forward(exchange, chain, "ANON:" + ip);
        }

        if (isLoginAttempt(request)) {
            return handleLoginAttempt(exchange, chain);
        }

        log.warn("Rejecting unauthenticated request to {}", request.getPath());
        return reject(exchange);
    }

    private Optional<Claims> extractValidClaims(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return jwtVerifier.verify(authHeader.substring(7));
    }

    // /auth/register is the one explicit exception to "no valid JWT -> reject":
    // it's JWT-less by nature but isn't a login attempt either (Mode ANON).
    private boolean isPublicUnauthenticated(ServerHttpRequest request) {
        return HttpMethod.POST.equals(request.getMethod())
                && "/auth/register".equals(request.getPath().value());
    }

    private boolean isLoginAttempt(ServerHttpRequest request) {
        return HttpMethod.POST.equals(request.getMethod())
                && "/auth/login".equals(request.getPath().value());
    }

    private Mono<Void> handleLoginAttempt(ServerWebExchange exchange, GatewayFilterChain chain) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    String ip = resolveClientIp(exchange);
                    String username = normalizeUsername(extractUsername(bytes));
                    String identity = "ATTEMPT:" + ip + "," + username;
                    log.info("Resolved login attempt, identity={}", identity);

                    ServerHttpRequest requestWithHeader = exchange.getRequest().mutate()
                            .header(IDENTITY_HEADER, identity)
                            .build();

                    // Body was already consumed above (WebFlux bodies are single-read) --
                    // replay the same bytes so auth-service still gets an intact LoginRequest.
                    ServerHttpRequest repeatableRequest = new ServerHttpRequestDecorator(requestWithHeader) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            DataBufferFactory factory = exchange.getResponse().bufferFactory();
                            return Flux.just(factory.wrap(bytes));
                        }
                    };

                    return chain.filter(exchange.mutate().request(repeatableRequest).build());
                });
    }

    private String extractUsername(byte[] bodyBytes) {
        try {
            Map<?, ?> parsed = objectMapper.readValue(bodyBytes, Map.class);
            Object username = parsed.get("username");
            return username != null ? username.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String normalizeUsername(String raw) {
        String trimmed = raw.strip();
        String truncated = trimmed.length() > MAX_USERNAME_LENGTH
                ? trimmed.substring(0, MAX_USERNAME_LENGTH)
                : trimmed;
        return truncated.toLowerCase();
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        return exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, String identity) {
        log.info("Allowing request to {}, identity={}", exchange.getRequest().getPath(), identity);
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(IDENTITY_HEADER, identity)
                .build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    // Must run before every other filter: identity has to exist before any
    // downstream filter (e.g. DecisionCacheFilter) can make a decision about it.
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}