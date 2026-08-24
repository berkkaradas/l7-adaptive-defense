package com.karadas.l7defense.riskengine.decision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Kararı l7.decisions'a yazar.
 *
 * <p>SignalPublisher'ın aynası ama dayanıklılık tercihi tam tersi (Karar Kaydı
 * 4.12.6). Orada kayıp kabul edilebilirdi: binlerce gözlemden biri düşse pencere
 * istatistiksel olarak aynı kalıyor, ve karşılığında event loop'un asla
 * beklememesi garanti ediliyordu. Burada kaybolan mesaj o kimliğin cezasının
 * tamamı demek — kimse tekrar göndermeyecek, Gateway hiçbir şey uygulamayacak.
 * Risk Engine cold path'te olduğu için beklemenin bedeli yalnızca gecikme.
 */
@Component
public class DecisionPublisher {

    private static final Logger log = LoggerFactory.getLogger(DecisionPublisher.class);

    private final KafkaTemplate<String, DecisionEvent> kafkaTemplate;
    private final Clock clock;
    private final String topic;

    public DecisionPublisher(KafkaTemplate<String, DecisionEvent> kafkaTemplate,
                             Clock clock,
                             @Value("${app.kafka.decisions-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.topic = topic;
    }

    public void publish(RiskDecision decision) {
        DecisionEvent event = DecisionEvent.from(decision, clock.instant());

        log.warn("Decision identity={} -> {} [{} / {}] score={} validUntil={}",
                event.identity(), event.decision(), event.attackType(),
                event.severity(), event.score(), event.validUntil());

        // Key olarak identity: tek partition'da zaten sıralama garantili, ama
        // ileride partition sayısı artarsa aynı kimliğin kararları yine aynı
        // partition'a düşsün diye şimdiden doğru anahtarı veriyoruz.
        kafkaTemplate.send(topic, event.identity(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // Sayıp örneklemiyoruz (3.11.3'ün tersine): bu hacimde
                        // her tek kayıp başlı başına anlamlı.
                        log.error("Decision NOT delivered identity={} decision={} "
                                        + "— Gateway will not apply this mitigation",
                                event.identity(), event.decision(), ex);
                    }
                });
    }
}