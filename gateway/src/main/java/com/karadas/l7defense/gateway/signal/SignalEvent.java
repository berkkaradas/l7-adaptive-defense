package com.karadas.l7defense.gateway.signal;

import com.karadas.l7defense.gateway.cache.Decision;

import java.time.Instant;

/**
 * One observed request outcome, published to the l7.signals topic.
 *
 * <p>Purely observational: emitted after the response has been produced, and never
 * able to influence it. The Risk Engine consumes these to maintain a per-identity
 * sliding window (Design Decisions Log, Section 3).
 *
 * @param identity          resolved identity string; also the Kafka partition key
 * @param timestamp         when the request completed
 * @param ip                caller IP — metadata only, deliberately not part of the identity key
 * @param path              request path, without query string
 * @param status            HTTP status returned to the client
 * @param latencyMs         time spent in the downstream call, excluding any tarpit delay
 * @param mitigationApplied what this Gateway did to the request, so the Risk Engine can
 *                          subtract its own effects from its own inputs
 * @param source            which component observed this; always "gateway" for now
 */
public record SignalEvent(
        String identity,
        Instant timestamp,
        String ip,
        String path,
        Integer status,
        long latencyMs,
        Decision mitigationApplied,
        String source
) {
}