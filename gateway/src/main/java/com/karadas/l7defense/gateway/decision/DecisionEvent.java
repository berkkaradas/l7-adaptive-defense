package com.karadas.l7defense.gateway.decision;

import com.karadas.l7defense.gateway.cache.Decision;

import java.time.Instant;

/**
 * l7.decisions üzerinden gelen mesajın Gateway'deki okuması.
 *
 * <p>Risk Engine'deki DecisionEvent ile alan alan aynı — bilinçli kopya, 6.6'daki
 * JwtVerifier ile aynı gerekçe. Ortak bir modül, iki servisi tek bir sürüme
 * zincirlerdi; kopya ise tel formatını gerçek kontrat haline getiriyor.
 *
 * <p>Gateway'in gerçekten ihtiyacı olan üç alan: identity, decision, validUntil.
 * Kalanı yalnızca log'a düşüyor — "neden cezalandırıldı" sorusunu Gateway
 * loglarından da cevaplayabilmek için (4.12.1).
 *
 * @param attackType Risk Engine'deki AttackType enum'ının String hali. Bilerek
 *                   enum olarak kopyalanmadı: Gateway saldırı taksonomisi
 *                   hakkında hiçbir muhakeme yapmıyor, bu değeri sadece taşıyor.
 *                   Enum yazsaydık, sahip olmadığımız bir anlayışı ima eder ve
 *                   senkron tutulacak iki tip daha yaratırdık.
 * @param severity   aynı gerekçe.
 */
public record DecisionEvent(
        String identity,
        Decision decision,
        Instant validUntil,
        String attackType,
        String severity,
        int score,
        Instant issuedAt,
        String source
) {
}