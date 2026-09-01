package com.example.ledger.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Single topic "account-events", partitioned by aggregateId (accountId) via
 * the producer's message key (see OutboxPublisher) - this is what guarantees
 * ordering FOR A GIVEN ACCOUNT. Kafka does NOT guarantee global ordering
 * across partitions/accounts, and this design never needs it to: two
 * different accounts' events being processed out of order relative to each
 * other is harmless, since they're independent aggregates.
 *
 * 6 partitions is an arbitrary demo value - real partition count should be
 * chosen based on target consumer parallelism, not guessed.
 */
@Configuration
public class KafkaTopicConfig {

    public static final String ACCOUNT_EVENTS_TOPIC = "account-events";
    public static final String ACCOUNT_EVENTS_DLT = "account-events.DLT";

    @Bean
    public NewTopic accountEventsTopic() {
        return TopicBuilder.name(ACCOUNT_EVENTS_TOPIC).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic accountEventsDlt() {
        return TopicBuilder.name(ACCOUNT_EVENTS_DLT).partitions(1).replicas(1).build();
    }
}
