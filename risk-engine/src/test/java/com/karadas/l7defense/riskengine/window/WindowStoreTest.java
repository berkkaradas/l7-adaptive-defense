package com.karadas.l7defense.riskengine.window;

import com.karadas.l7defense.riskengine.signal.Decision;
import com.karadas.l7defense.riskengine.signal.SignalEvent;
import com.karadas.l7defense.riskengine.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kayan pencerenin testi.
 *
 * <p>Bu testlerin elle karşılığı 180 saniye beklemek. Clock enjekte edildiği
 * için (4.1) saati ileri sarıp mikrosaniyede aynı şeyi doğruluyoruz.
 */
class WindowStoreTest {

    private static final Instant T0 = Instant.parse("2026-08-24T12:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(180);

    /**
     * Elle ileri sarılabilen saat. Clock soyut bir sınıf, üç metodu var — test
     * için kendi implementasyonunu yazmak Mockito'dan hem daha kısa hem daha açık.
     */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private static WindowStore store(Clock clock) {
        return new WindowStore(clock, WINDOW, 10_000, 50_000, 100, 100);
    }

    private static SignalEvent signal(String identity, Instant at,
                                      Decision applied, Integer status, String path) {
        return new SignalEvent(identity, at, "1.2.3.4", path, status, 5L, applied, "gateway");
    }

    private static SignalEvent loginFailure(String identity, Instant at) {
        return signal(identity, at, Decision.ALLOW, 401, "/auth/login");
    }

    private static int count(WindowStore store, String identity, SignalKind kind) {
        return store.countsFor(identity).getOrDefault(kind, 0);
    }

    // ------------------------------------------------------------ temel sayım

    @Test
    void eventsInsideTheWindowAreCounted() {
        WindowStore store = store(new MutableClock(T0));
        store.record(loginFailure("ATTEMPT:1.2.3.4,omer", T0));
        store.record(loginFailure("ATTEMPT:1.2.3.4,omer", T0));

        assertEquals(2, count(store, "ATTEMPT:1.2.3.4,omer", SignalKind.LOGIN_FAILURE));
    }

    // --------------------------------------------------------- zaman aşımı

    @Test
    void eventsOlderThanTheWindowAreExcludedEvenBeforePurgeRuns() {
        MutableClock clock = new MutableClock(T0);
        WindowStore store = store(clock);
        store.record(loginFailure("ATTEMPT:1.2.3.4,omer", T0));

        clock.advance(Duration.ofSeconds(181));

        // purgeExpired() bilerek ÇAĞRILMIYOR. Purge 60 saniyede bir koşuyor,
        // yani okuma anında pencerede her zaman süresi geçmiş kayıtlar olabilir.
        // countsSince cutoff'u okuma anında yeniden uyguladığı için skor doğru.
        assertEquals(0, count(store, "ATTEMPT:1.2.3.4,omer", SignalKind.LOGIN_FAILURE));
    }

    @Test
    void purgeRemovesExpiredEventsAndForgetsTheIdentity() {
        MutableClock clock = new MutableClock(T0);
        WindowStore store = store(clock);
        store.record(loginFailure("ATTEMPT:1.2.3.4,omer", T0));

        clock.advance(Duration.ofSeconds(181));
        store.purgeExpired();

        // Sayım zaten sıfırdı; buradaki fark belleğin de geri alınması —
        // kimlik artık cache'te hiç yok, sadece boş bir pencere tutuyor değil.
        assertTrue(store.countsFor("ATTEMPT:1.2.3.4,omer").isEmpty());
    }

    @Test
    void eventsRightOnTheBoundaryAreStillCounted() {
        MutableClock clock = new MutableClock(T0);
        WindowStore store = store(clock);
        store.record(loginFailure("ATTEMPT:1.2.3.4,omer", T0));

        // Tam 180 saniye: cutoff == olayın zamanı. countsSince ">=" kullanıyor,
        // yani sınırdaki olay hâlâ pencerenin içinde.
        clock.advance(WINDOW);

        assertEquals(1, count(store, "ATTEMPT:1.2.3.4,omer", SignalKind.LOGIN_FAILURE));
    }

    // ------------------------------------------------------ neyin girip girmediği

    @Test
    void ignoredSignalsNeverEnterTheWindow() {
        WindowStore store = store(new MutableClock(T0));
        store.record(signal("AUTH:1", T0, Decision.ALLOW, 200, "/orders"));

        assertTrue(store.countsFor("AUTH:1").isEmpty());
    }

    @Test
    void persistenceSignalsDoEnterTheWindow() {
        // 4.13 regresyonu: record() yalnızca IGNORED'ı eliyor. MITIGATED_RETRY
        // eklendiğinde WindowStore'a hiç dokunmamamızın sebebi buydu — ve bu
        // testin sebebi de birinin ileride oraya bir eleme eklemesi ihtimali.
        WindowStore store = store(new MutableClock(T0));
        store.record(signal("AUTH:1", T0, Decision.RATE_LIMIT, 429, "/orders"));

        assertEquals(1, count(store, "AUTH:1", SignalKind.MITIGATED_RETRY));
    }

    // --------------------------------------------------- global hata penceresi

    @Test
    void serverErrorsAreCountedAgainstTheIdentityAndSystemWide() {
        WindowStore store = store(new MutableClock(T0));
        store.record(signal("AUTH:1", T0, Decision.ALLOW, 503, "/orders"));
        store.record(signal("AUTH:2", T0, Decision.ALLOW, 503, "/orders"));

        // Aynı olay iki yerde sayılıyor: kimliğin kendi penceresinde bir kez,
        // sistem genelinde bir kez. Atıf testi (4.7) iki sayıya da ihtiyaç duyuyor.
        assertEquals(1, count(store, "AUTH:1", SignalKind.SERVER_ERROR));
        assertEquals(2, store.globalErrorCount());
    }
}