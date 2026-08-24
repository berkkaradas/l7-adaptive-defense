package com.karadas.l7defense.riskengine.decision;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

/**
 * l7.decisions topic'i. Gateway'deki KafkaTopicConfig'in aynadaki karşılığı:
 * topic'i onu üreten servis tanımlar (Karar Kaydı 3.10.1).
 */
@Configuration
public class DecisionTopicConfig {

    // Tek partition. Signals'ta üç seçmiştik çünkü orada her istek bir mesaj
    // üretiyor; burada yalnızca cezalar yayınlanıyor, ALLOW hiç gönderilmiyor.
    // Karşılığında topic genelinde tam sıralama kazanıyoruz: iki kararın ters
    // sırada uygulanması diye bir ihtimal kalmıyor (4.12.2).
    private static final int DECISIONS_PARTITIONS = 1;

    // Tek broker'lık geliştirme kümesi: replike edilecek bir yer yok.
    private static final int REPLICATION_FACTOR = 1;

    // Signals 24 saat, kararlar 7 gün. Precision/recall hesabını deney bittikten
    // günler sonra yapacağız ve hacim zaten küçük (4.12.5).
    private static final Duration DECISIONS_RETENTION = Duration.ofDays(7);

    @Bean
    NewTopic decisionsTopic(@Value("${app.kafka.decisions-topic}") String topic) {
        return TopicBuilder.name(topic)
                .partitions(DECISIONS_PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(DECISIONS_RETENTION.toMillis()))
                .build();
    }
}