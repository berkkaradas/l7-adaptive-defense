package com.karadas.l7defense.riskengine.scoring;

import com.karadas.l7defense.riskengine.signal.SignalKind;
import com.karadas.l7defense.riskengine.window.WindowStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Skorlamanın testi: ağırlıklar, toplam-baskın ayrımı ve 5xx atıfı.
 *
 * <p>WindowStore mock'lanıyor — bu testin konusu pencerenin doğru sayıp
 * saymadığı değil (o WindowStoreTest'in işi), sayılardan doğru skor çıkıp
 * çıkmadığı.
 */
class ScoringServiceTest {

    private static final String ID = "ATTEMPT:1.2.3.4,omer";

    /**
     * Ağırlıklar testin kendi içinde sabit — application.yaml okunmuyor.
     * Bilerek: config'i değiştirmek testi kırmamalı. Test aritmetiği
     * doğruluyor, o anki ayarları değil.
     */
    private static ScoringService service(WindowStore store) {
        return new ScoringService(store,
                10,   // login-failure
                5,    // baseline-throttle
                3,    // unauthenticated
                15,   // server-error
                20,   // system-wide-error-threshold
                0.5,  // concentration-threshold
                5);   // mitigated-retry
    }

    private static WindowStore storeWith(Map<SignalKind, Integer> counts, int globalErrors) {
        WindowStore store = mock(WindowStore.class);
        when(store.countsFor(ID)).thenReturn(counts);
        when(store.globalErrorCount()).thenReturn(globalErrors);
        return store;
    }

    // ---------------------------------------------------- 4.5.1: toplam vs baskın

    @Test
    void thresholdUsesTheTotalWhileMitigationUsesTheDominantType() {
        // Projenin en önemli skorlama kuralı. Eşiği toplam belirliyor,
        // mitigation türünü baskın tip. En yüksek tipi eşikle karşılaştırsaydık
        // bu kimlik 110'da kalır, 120 eşiğini hiç geçemez ve TARPIT'i hiç
        // görmezdik.
        WindowStore store = storeWith(Map.of(
                SignalKind.LOGIN_FAILURE, 11,        // 11 x 10 = 110  CREDENTIAL
                SignalKind.BASELINE_THROTTLE, 2      //  2 x  5 =  10  VOLUMETRIC
        ), 0);

        RiskScore score = service(store).scoreOf(ID);

        assertEquals(120, score.totalScore());
        assertEquals(AttackType.CREDENTIAL_ATTACK, score.dominantType());
        assertEquals(110, score.dominantScore());
    }

    @Test
    void unauthenticatedRequestsCountAsVolumetric() {
        WindowStore store = storeWith(Map.of(
                SignalKind.BASELINE_THROTTLE, 4,     // 20
                SignalKind.UNAUTHENTICATED, 5        // 15
        ), 0);

        assertEquals(35, service(store).scoreOf(ID).byType().get(AttackType.VOLUMETRIC));
    }

    // ------------------------------------------------------- 4.13: ısrar puanı

    @Test
    void persistenceRaisesTheTotalWithoutTouchingAnyAttackType() {
        // 4.13'ün özü. Israr şiddeti yükseltiyor ama hangi cezanın verileceğini
        // değiştirmiyor: baskın tip CREDENTIAL_ATTACK kalıyor, yani bu kimlik
        // SEVERE'de DROP değil TARPIT alacak.
        WindowStore store = storeWith(Map.of(
                SignalKind.LOGIN_FAILURE, 5,         //  5 x 10 = 50  CREDENTIAL
                SignalKind.MITIGATED_RETRY, 14       // 14 x  5 = 70  tipsiz
        ), 0);

        RiskScore score = service(store).scoreOf(ID);

        assertEquals(70, score.persistenceScore());
        assertEquals(120, score.totalScore());
        assertEquals(AttackType.CREDENTIAL_ATTACK, score.dominantType());
        assertEquals(50, score.dominantScore());
        // Israr hiçbir kovaya yazılmadı:
        assertEquals(0, score.byType().get(AttackType.VOLUMETRIC));
        assertEquals(0, score.byType().get(AttackType.RESOURCE_EXHAUSTION));
    }

    // ------------------------------------------------- 4.7: 5xx atıf testi

    @Test
    void serverErrorsAreScoredWhenNothingWidespreadIsHappening() {
        // Durum 1: sistem genelinde 10 hata var, eşik 20. Yaygın bir olay yok,
        // dolayısıyla bu hatalar onları tetikleyene ait.
        WindowStore store = storeWith(Map.of(SignalKind.SERVER_ERROR, 3), 10);

        assertEquals(45, service(store).scoreOf(ID).byType().get(AttackType.RESOURCE_EXHAUSTION));
    }

    @Test
    void serverErrorsAreScoredWhenThisIdentityCausedMostOfThem() {
        // Durum 2: yaygın bir olay var (40 >= 20) ama %62'si bu kimlikten.
        // Bu dal olmasaydı saldırgan önce sistem geneli hata oranını kendi
        // yükseltir, sonra yarattığı gürültünün içinde saklanırdı.
        WindowStore store = storeWith(Map.of(SignalKind.SERVER_ERROR, 25), 40);

        assertEquals(375, service(store).scoreOf(ID).byType().get(AttackType.RESOURCE_EXHAUSTION));
    }

    @Test
    void serverErrorsAreNotScoredWhenTheyAreWidespreadAndDiffuse() {
        // Durum 3: yaygın (40 >= 20) ve dağınık (%12.5 < %50). Bu bir sistem
        // durumu, kimse suçlanmıyor.
        //
        // Bu senaryo canlı sistemde hiç test edilemedi (bkz. 15.2) — eşzamanlı
        // üç ayrı kimliğin hata üretmesi gerekiyor. Burada tek satır.
        WindowStore store = storeWith(Map.of(SignalKind.SERVER_ERROR, 5), 40);

        assertEquals(0, service(store).scoreOf(ID).byType().get(AttackType.RESOURCE_EXHAUSTION));
    }

    // ------------------------------------------------------------ kenar durum

    @Test
    void anIdentityWithNoHistoryScoresNothing() {
        WindowStore store = storeWith(Map.of(), 0);

        RiskScore score = service(store).scoreOf(ID);

        assertEquals(0, score.totalScore());
        assertNull(score.dominantType());
        assertFalse(score.hasEvidence());
    }
}