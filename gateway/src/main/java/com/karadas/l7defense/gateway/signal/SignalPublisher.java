package com.karadas.l7defense.gateway.signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;


@Component
public class SignalPublisher {

    private static final Logger log = LoggerFactory.getLogger(SignalPublisher.class);


    private static final long REPORT_INTERVAL_MS = 10_000L;

    private final KafkaTemplate<String, SignalEvent> kafkaTemplate;
    private final String topic;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastReportAt = new AtomicLong(System.currentTimeMillis());

    public SignalPublisher(KafkaTemplate<String, SignalEvent> kafkaTemplate,
                           @Value("${app.kafka.signals-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * Hands the signal to the Kafka producer and returns immediately.
     * Never throws, never blocks, never reports failure to the caller.
     */
    public void publish(SignalEvent signal) {
        try {
            kafkaTemplate.send(topic, signal.identity(), signal)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            recordDrop();
                        }
                    });
        } catch (Exception ex) {
            recordDrop();
        }
    }

    private void recordDrop() {
        dropped.incrementAndGet();

        long now = System.currentTimeMillis();
        long last = lastReportAt.get();

        if (now - last >= REPORT_INTERVAL_MS && lastReportAt.compareAndSet(last, now)) {
            long count = dropped.getAndSet(0L);
            log.warn("Kafka unreachable — dropped {} signal(s) in the last {} ms",
                    count, now - last);
        }
    }
}