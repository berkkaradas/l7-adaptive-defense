package com.karadas.l7defense.riskengine.decision;

/**
 * Bir kimliğin o anki tehdit seviyesi.
 *
 * <p>Skor ile mitigation arasındaki ara katman. Doğrudan skordan Decision'a
 * atlasaydık her saldırı tipi için farklı eşleme yapamazdık — aynı şiddet,
 * tipe göre farklı tepki gerektiriyor (Karar Kaydı 4.5).
 */
public enum Severity {
    NONE,
    MODERATE,
    SEVERE
}