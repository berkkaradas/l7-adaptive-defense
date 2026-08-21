package com.karadas.l7defense.riskengine.decision;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Her kimliğin o anki seviyesini hatırlar.
 *
 * <p>Bu sınıf hysteresis'in var olma sebebi. Aynı skor, nereden gelindiğine
 * göre farklı sonuç veriyor: 25 puan, aşağıdan gelene NONE, yukarıdan gelene
 * MODERATE demek. Bunu bilebilmek için önceki seviyeyi saklamak zorundayız —
 * sistemdeki ilk gerçek durum makinesi burası.
 *
 * <p>Window store gibi sınırlı ve bölümlenmiş (Karar Kaydı 9.9): kimlik
 * anahtarlarının bir kısmını saldırgan ürettiği için, o taraf dolsa bile
 * gerçek kullanıcıların kaydı atılmasın.
 */
@Component
public class DecisionStateStore {

    private final Cache<String, Severity> authenticated;
    private final Cache<String, Severity> unauthenticated;

    public DecisionStateStore(
            @Value("${app.store.authenticated-max}") long authenticatedMax,
            @Value("${app.store.unauthenticated-max}") long unauthenticatedMax,
            @Value("${app.decision.state-ttl}") Duration stateTtl) {
        // expireAfterAccess: uzun süre dokunulmayan seviye unutulur. Olmasaydı
        // sessizleşen bir kimlik sonsuza kadar MODERATE olarak hatırlanır, aylar
        // sonra döndüğünde hysteresis onu hâlâ "yukarıdan geliyor" sayardı.
        this.authenticated = Caffeine.newBuilder()
                .maximumSize(authenticatedMax)
                .expireAfterAccess(stateTtl)
                .build();
        this.unauthenticated = Caffeine.newBuilder()
                .maximumSize(unauthenticatedMax)
                .expireAfterAccess(stateTtl)
                .build();
    }

    /** Kayıt yoksa NONE — yani "hiç görülmemiş" ile "temiz" aynı şey. */
    public Severity currentLevel(String identity) {
        Severity level = cacheFor(identity).getIfPresent(identity);
        return level == null ? Severity.NONE : level;
    }

    public void update(String identity, Severity level) {
        if (level == Severity.NONE) {
            // NONE saklamanın anlamı yok: yokluk zaten NONE demek. Saklasaydık
            // temizlenen her kimlik bellekte yer tutmaya devam ederdi.
            cacheFor(identity).invalidate(identity);
        } else {
            cacheFor(identity).put(identity, level);
        }
    }

    private Cache<String, Severity> cacheFor(String identity) {
        return identity.startsWith("AUTH:") ? authenticated : unauthenticated;
    }
}