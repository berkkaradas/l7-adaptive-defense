package com.karadas.l7defense.gateway.signal;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;


@Configuration
public class KafkaTopicConfig {

    // Caps consumer parallelism permanently; cannot be raised without breaking
    // key-to-partition mapping. See Design Decisions Log 3.6.
    private static final int SIGNALS_PARTITIONS = 3;

    // Single-broker development cluster: nothing to replicate to.
    private static final int REPLICATION_FACTOR = 1;

    // Long enough to replay one experiment run without re-running the load test (3.7).
    private static final Duration SIGNALS_RETENTION = Duration.ofHours(24);

    @Bean
    NewTopic signalsTopic(@Value("${app.kafka.signals-topic}") String topic) {
        return TopicBuilder.name(topic)
                .partitions(SIGNALS_PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(SIGNALS_RETENTION.toMillis()))
                .build();
    }
}