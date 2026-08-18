package com.karadas.l7defense.riskengine.consumer;

import com.karadas.l7defense.riskengine.scoring.RiskScore;
import com.karadas.l7defense.riskengine.scoring.ScoringService;
import com.karadas.l7defense.riskengine.signal.SignalEvent;
import com.karadas.l7defense.riskengine.signal.SignalKind;
import com.karadas.l7defense.riskengine.window.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SignalConsumer {

    private static final Logger log = LoggerFactory.getLogger(SignalConsumer.class);

    private final WindowStore windowStore;
    private final ScoringService scoringService;

    public SignalConsumer(WindowStore windowStore, ScoringService scoringService) {
        this.windowStore = windowStore;
        this.scoringService = scoringService;
    }

    @KafkaListener(topics = "${app.kafka.signals-topic}", groupId = "risk-engine")
    public void onSignal(SignalEvent signal) {
        windowStore.record(signal);

        RiskScore score = scoringService.scoreOf(signal.identity());
        if (score.hasEvidence()) {
            log.info("identity={} kind={} -> total={} dominant={}({}) breakdown={}",
                    signal.identity(), SignalKind.of(signal),
                    score.totalScore(), score.dominantType(), score.dominantScore(),
                    score.byType());
        }
        else {
            log.debug("identity={} kind={} -> no evidence",
                    signal.identity(), SignalKind.of(signal));
        }
    }
}