package com.karadas.l7defense.riskengine.window;

import com.karadas.l7defense.riskengine.signal.SignalKind;

/**
 * One event inside a sliding window. The timestamp is epoch milliseconds rather
 * than an Instant — four times smaller, and all arithmetic is on epoch values
 */
public record WindowEntry(long epochMillis, SignalKind kind) {
}