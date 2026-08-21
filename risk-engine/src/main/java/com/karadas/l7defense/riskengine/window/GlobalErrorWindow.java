package com.karadas.l7defense.riskengine.window;

import java.util.ArrayDeque;

/**
 * A single window over server errors from every identity combined.
 *
 * <p>Per-identity windows answer "how many errors did this caller see". This one
 * answers "how many errors did the system produce at all" — the denominator the
 * attribution test needs (Design Decisions Log 4.7).
 *
 * <p>Only timestamps are stored: which identity produced an error is already
 * recorded in that identity's own window, so duplicating it here would be waste.
 */
public final class GlobalErrorWindow {

    private final ArrayDeque<Long> timestamps = new ArrayDeque<>();
    private final int maxEntries;

    public GlobalErrorWindow(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public synchronized void record(long epochMillis) {
        timestamps.addLast(epochMillis);
        while (timestamps.size() > maxEntries) {
            timestamps.removeFirst();
        }
    }

    public synchronized int purgeOlderThan(long cutoffMillis) {
        int removed = 0;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoffMillis) {
            timestamps.removeFirst();
            removed++;
        }
        return removed;
    }

    /** Applied again at read time so a count never depends on when the purge last ran. */
    public synchronized int countSince(long cutoffMillis) {
        int count = 0;
        for (Long t : timestamps) {
            if (t >= cutoffMillis) {
                count++;
            }
        }
        return count;
    }
}