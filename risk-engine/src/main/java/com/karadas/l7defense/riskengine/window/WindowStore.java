package com.karadas.l7defense.riskengine.window;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.karadas.l7defense.riskengine.signal.SignalEvent;
import com.karadas.l7defense.riskengine.signal.SignalKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Holds one sliding window per identity, partitioned by identity mode.
 *
 * <p>Authenticated identities and attacker-influenced ones live in separate bounded
 * caches (Design Decisions Log 9.9), so flooding the latter cannot evict entries
 * belonging to real users — a guarantee that a single shared bound cannot give.
 */
@Component
public class WindowStore {

    private static final Logger log = LoggerFactory.getLogger(WindowStore.class);

    private final Cache<String, IdentityWindow> authenticated;
    private final Cache<String, IdentityWindow> unauthenticated;
    private final Clock clock;
    private final Duration windowLength;
    private final int maxEventsPerIdentity;

    public WindowStore(Clock clock,
                       @Value("${app.window.length}") Duration windowLength,
                       @Value("${app.window.max-events-per-identity}") int maxEventsPerIdentity,
                       @Value("${app.store.authenticated-max}") long authenticatedMax,
                       @Value("${app.store.unauthenticated-max}") long unauthenticatedMax) {
        this.clock = clock;
        this.windowLength = windowLength;
        this.maxEventsPerIdentity = maxEventsPerIdentity;
        this.authenticated = Caffeine.newBuilder().maximumSize(authenticatedMax).build();
        this.unauthenticated = Caffeine.newBuilder().maximumSize(unauthenticatedMax).build();
    }

    public void record(SignalEvent signal) {
        SignalKind kind = SignalKind.of(signal);
        if (kind == SignalKind.IGNORED) {
            return;
        }
        // get(key, mappingFn) is atomic — two threads cannot each create a window and
        // have one silently overwrite the other along with its events.
        IdentityWindow window = cacheFor(signal.identity())
                .get(signal.identity(), k -> new IdentityWindow(maxEventsPerIdentity));
        window.add(new WindowEntry(signal.timestamp().toEpochMilli(), kind));
    }

    public Map<SignalKind, Integer> countsFor(String identity) {
        IdentityWindow window = cacheFor(identity).getIfPresent(identity);
        return window == null ? Map.of() : window.countsSince(cutoff());
    }

    /**
     * Removes expired events from every identity, and drops identities left empty.
     * Runs on a scheduler thread, concurrently with the Kafka listener — which is why
     * IdentityWindow is synchronised.
     */
    @Scheduled(fixedDelayString = "${app.window.purge-interval}")
    public void purgeExpired() {
        long cutoff = cutoff();
        int removedEvents = 0;
        int removedIdentities = 0;

        for (Cache<String, IdentityWindow> cache : java.util.List.of(authenticated, unauthenticated)) {
            for (Map.Entry<String, IdentityWindow> e : cache.asMap().entrySet()) {
                removedEvents += e.getValue().purgeOlderThan(cutoff);
                if (e.getValue().isEmpty()) {
                    cache.invalidate(e.getKey());
                    removedIdentities++;
                }
            }
        }

        if (removedEvents > 0 || removedIdentities > 0) {
            log.debug("purge: {} events, {} identities dropped; tracking {} auth / {} other",
                    removedEvents, removedIdentities,
                    authenticated.estimatedSize(), unauthenticated.estimatedSize());
        }
    }

    private long cutoff() {
        return clock.millis() - windowLength.toMillis();
    }

    private Cache<String, IdentityWindow> cacheFor(String identity) {
        return identity.startsWith("AUTH:") ? authenticated : unauthenticated;
    }
}