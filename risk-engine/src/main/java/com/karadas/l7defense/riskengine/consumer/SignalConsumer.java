package com.karadas.l7defense.riskengine.consumer;

import com.karadas.l7defense.riskengine.signal.SignalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads the signals topic. For now it only proves the pipeline works end to end;
 * the sliding-window store and scoring are added in the next step.
 *
 * <p>Runs with concurrency 1 (Design Decisions Log 4.8) — a single consumer thread
 * reading all three partitions, which removes an entire class of concurrency bug
 * from this iteration.
 */
@Component
public class SignalConsumer {

    private static final Logger log = LoggerFactory.getLogger(SignalConsumer.class);

    @KafkaListener(topics = "${app.kafka.signals-topic}", groupId = "risk-engine")
    public void onSignal(SignalEvent signal) {
        log.info("identity={} path={} status={} latency={}ms mitigation={} at={}",
                signal.identity(), signal.path(), signal.status(),
                signal.latencyMs(), signal.mitigationApplied(), signal.timestamp());
    }
}
