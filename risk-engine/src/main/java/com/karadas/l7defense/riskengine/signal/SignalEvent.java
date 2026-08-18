package com.karadas.l7defense.riskengine.signal;

import java.time.Instant;

/**
 * One observed request outcome, as published by the Gateway.
 *
 * <p>Field-for-field copy of the Gateway's record. The two must stay in step; the
 * wire format is the contract between them (Design Decisions Log 3.1).
 *
 * @param identity          resolved identity string; also the Kafka partition key
 * @param timestamp         when the request completed — scoring uses this, never the
 *                          consumer's clock (Design Decisions Log 4.1)
 * @param ip                caller IP, metadata only
 * @param path              request path, without query string
 * @param status            HTTP status returned, or null when the connection was dropped
 * @param latencyMs         downstream time, already excluding any tarpit delay
 * @param mitigationApplied what the Gateway did, so its own effects can be excluded
 *                          from scoring (Design Decisions Log 3.1.1)
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
