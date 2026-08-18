package com.karadas.l7defense.riskengine.window;

import com.karadas.l7defense.riskengine.signal.SignalKind;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;

/**
 * The sliding window for one identity: events in arrival order, oldest first.
 *
 * <p>Every method is synchronised because two threads reach this object — the Kafka
 * listener adding events, and the scheduled purge removing them. The lock is per
 * identity, so contention is effectively zero; there is no global lock anywhere.
 */
public final class IdentityWindow {

    private final ArrayDeque<WindowEntry> entries = new ArrayDeque<>();
    private final int maxEntries;

    public IdentityWindow(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * Appends an event. Signals for one identity always arrive in order because they
     * share a Kafka partition, so the deque stays sorted by timestamp.
     */
    public synchronized void add(WindowEntry entry) {
        entries.addLast(entry);
        // Per-identity cap: a partition-level bound is not enough, because a single
        // aggressive identity can consume hundreds of kilobytes on its own (Log 4.3).
        while (entries.size() > maxEntries) {
            entries.removeFirst();
        }
    }

    /** Drops everything older than the cutoff. Returns how many were removed. */
    public synchronized int purgeOlderThan(long cutoffMillis) {
        int removed = 0;
        while (!entries.isEmpty() && entries.peekFirst().epochMillis() < cutoffMillis) {
            entries.removeFirst();
            removed++;
        }
        return removed;
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized int size() {
        return entries.size();
    }

    /**
     * Counts per kind for events at or after the cutoff. Purging is expected to have
     * happened already, but the cutoff is applied again here so a read is correct even
     * if the purge has not run yet.
     */
    public synchronized Map<SignalKind, Integer> countsSince(long cutoffMillis) {
        Map<SignalKind, Integer> counts = new EnumMap<>(SignalKind.class);
        for (WindowEntry e : entries) {
            if (e.epochMillis() >= cutoffMillis) {
                counts.merge(e.kind(), 1, Integer::sum);
            }
        }
        return counts;
    }
}