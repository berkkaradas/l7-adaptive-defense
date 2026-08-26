package com.karadas.l7defense.riskengine.signal;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sınıflandırmanın regresyon testi.
 *
 * <p>Bu metodu üç oturumda iki kez değiştirdik (4.13 ve 4.13.1). Her ikisi de
 * sistemi çalıştırıp loglara bakarak bulundu; bu test onları dondurup saklıyor.
 * Hiçbir bağımlılığı yok — saf bir fonksiyon, mikrosaniyede koşuyor.
 */
class SignalKindTest {

    private static SignalEvent signal(Decision applied, Integer status, String path) {
        return new SignalEvent("ATTEMPT:1.2.3.4,omer", Instant.now(), "1.2.3.4",
                path, status, 5L, applied, "gateway");
    }

    // ---------------------------------------------------------- kendi yankımız

    @Test
    void requestSentUnderRateLimitCountsAsPersistence() {
        assertEquals(SignalKind.MITIGATED_RETRY,
                SignalKind.of(signal(Decision.RATE_LIMIT, 429, "/auth/login")));
    }

    @Test
    void requestSentUnderDropCountsAsPersistence() {
        // DROP yolunda status yok — bağlantı koparıldığı için cevap hiç yazılmadı.
        assertEquals(SignalKind.MITIGATED_RETRY,
                SignalKind.of(signal(Decision.DROP, null, "/orders")));
    }

    // ------------------------------------------------ 4.13.1: tarpit yankı DEĞİL

    @Test
    void tarpittedLoginFailureIsRealEvidence() {
        // Bu testin varlık sebebi: tarpit isteği engellemiyor, geciktiriyor.
        // İstek auth-service'e ulaşıyor ve 401 gerçekten dönüyor. Bunu yankı
        // sayarsak saldırganın gerçek davranışını çöpe atarız (4.13.1).
        assertEquals(SignalKind.LOGIN_FAILURE,
                SignalKind.of(signal(Decision.TARPIT, 401, "/auth/login")));
    }

    @Test
    void tarpittedSuccessIsIgnored() {
        // Tarpit altında başarılı bir istek de gerçek — ve gerçekte skorlanacak
        // bir şey yok. TARPIT'in kendisi hiçbir anlam taşımıyor, sonuç taşıyor.
        assertEquals(SignalKind.IGNORED,
                SignalKind.of(signal(Decision.TARPIT, 200, "/orders")));
    }

    // ------------------------------------------------------ bağımsız gözlemler

    @Test
    void bucketRejectionIsIndependentEvidence() {
        // Aynı 429, ama bizim kararımız değil — bucket'ın taşması. Saldırgan
        // açısından RATE_LIMIT'ten ayırt edilemez, bizim açımızdan tamamen farklı.
        assertEquals(SignalKind.BASELINE_THROTTLE,
                SignalKind.of(signal(Decision.BASELINE_THROTTLE, 429, "/orders")));
    }

    @Test
    void unauthorizedOnLoginIsCredentialEvidence() {
        assertEquals(SignalKind.LOGIN_FAILURE,
                SignalKind.of(signal(Decision.ALLOW, 401, "/auth/login")));
    }

    @Test
    void unauthorizedElsewhereIsNotCredentialEvidence() {
        // Aynı statü kodu, farklı anlam: burada şifre denenmiyor, token eksik.
        assertEquals(SignalKind.UNAUTHENTICATED,
                SignalKind.of(signal(Decision.ALLOW, 401, "/orders")));
    }

    @Test
    void serverErrorIsResourceEvidence() {
        assertEquals(SignalKind.SERVER_ERROR,
                SignalKind.of(signal(Decision.ALLOW, 503, "/orders")));
    }

    @Test
    void successfulRequestScoresNothing() {
        assertEquals(SignalKind.IGNORED,
                SignalKind.of(signal(Decision.ALLOW, 200, "/orders")));
    }
}