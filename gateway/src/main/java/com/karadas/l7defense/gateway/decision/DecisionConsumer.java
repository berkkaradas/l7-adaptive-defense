package com.karadas.l7defense.gateway.decision;

import com.karadas.l7defense.gateway.cache.CachedDecision;
import com.karadas.l7defense.gateway.cache.DecisionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Risk Engine'in kararlarını dinler ve yerel karar cache'ine yazar.
 *
 * <p>Döngünün kapandığı yer burası. Gateway'in ilk consumer'ı: bugüne kadar
 * yalnızca sinyal üretiyordu, artık geri besleme de alıyor.
 *
 * <p>Thread notu: bu metot Kafka listener container'ının kendi thread'inde
 * çalışıyor, Netty event loop'unda DEĞİL. İki dünya birbirini hiç çağırmıyor;
 * aralarındaki tek temas noktası, ikisinin de güvenle kullanabildiği Caffeine
 * cache'i. Event loop okuyor, bu thread yazıyor, kimse kimseyi beklemiyor.
 */
@Component
public class DecisionConsumer {

    private static final Logger log = LoggerFactory.getLogger(DecisionConsumer.class);

    private final DecisionCache decisionCache;

    public DecisionConsumer(DecisionCache decisionCache) {
        this.decisionCache = decisionCache;
    }

    @KafkaListener(topics = "${app.kafka.decisions-topic}", groupId = "gateway")
    public void onDecision(DecisionEvent event) {
        // Süresi geçmiş karar cache'e hiç girmiyor (4.12.7). Girseydi davranış
        // yine doğru olurdu — validUntil otoriter (9.2), okunduğunda yok sayılırdı
        // — ama cache sınırlı (9.9) ve consumer lag'inden sonra gelen bir yığın
        // ölü kayıt, canlı cezaları evict edebilirdi.
        if (!event.validUntil().isAfter(Instant.now())) {
            log.debug("Discarding expired decision identity={} validUntil={}",
                    event.identity(), event.validUntil());
            return;
        }

        decisionCache.put(event.identity(),
                new CachedDecision(event.decision(), event.validUntil()));

        log.info("Mitigation armed identity={} decision={} validUntil={} "
                        + "(score={} type={} severity={})",
                event.identity(), event.decision(), event.validUntil(),
                event.score(), event.attackType(), event.severity());
    }
}