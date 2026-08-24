package com.karadas.l7defense.riskengine.signal;

/**
 * What a signal means for scoring. Classification happens once, on arrival, so the
 * window stores a small enum rather than the whole event (Design Decisions Log 4.3).
 */
public enum SignalKind {

    LOGIN_FAILURE,
    BASELINE_THROTTLE,
    UNAUTHENTICATED,
    SERVER_ERROR,
    MITIGATED_RETRY,
    IGNORED;

    public static SignalKind of(SignalEvent signal) {
        Decision applied = signal.mitigationApplied();

        // Ceza altındayken atılmış bir istek. Dönen cevabı biz ürettiğimiz için
        // cevap bir gözlem değil — ama isteğin ATILMIŞ olması başlı başına kanıt.
        // 429 gördükten sonra devam etmek ısrardır; düzgün bir istemci bunu
        // yapmaz, geri çekilir (Karar Kaydı 4.13).
        if (applied == Decision.RATE_LIMIT || applied == Decision.DROP) {
            return MITIGATED_RETRY;
        }

        // TARPIT bilerek yukarıdaki listede yok. Tarpit isteği engellemiyor,
        // yalnızca 3 saniye geciktiriyor: istek gerçekten auth-service'e gidiyor
        // ve dönen 401 gerçek bir başarısız denemedir. Aşağıdaki statü kontrolleri
        // onu zaten doğru sınıflandırıyor, ek bir dala gerek yok (4.13.1).

        // Not a verdict we issued — independent evidence of exceeding the rate.
        if (applied == Decision.BASELINE_THROTTLE) {
            return BASELINE_THROTTLE;
        }
        if (applied == Decision.UNAUTHENTICATED) {
            return UNAUTHENTICATED;
        }

        Integer status = signal.status();
        if (status == null) {
            return IGNORED;
        }
        if (status == 401) {
            return "/auth/login".equals(signal.path()) ? LOGIN_FAILURE : UNAUTHENTICATED;
        }
        if (status >= 500) {
            return SERVER_ERROR;
        }
        return IGNORED;
    }
}