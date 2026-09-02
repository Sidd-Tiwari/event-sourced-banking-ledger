package com.example.ledger.infrastructure.kafka;

import com.example.ledger.infrastructure.observability.LedgerMetrics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Map;

/**
 * Retry policy: exponential backoff starting at 1s, doubling, capped at 3
 * attempts total, then the message is published to account-events.DLT via
 * DeadLetterPublishingRecoverer and the original offset is committed (so the
 * consumer doesn't get stuck retrying the same poison message forever).
 *
 * This distinguishes two failure classes, per design brief section 18:
 *   - Malformed/unparseable messages -> will NEVER succeed on retry ->
 *     ideally these'd be classified as non-retryable and sent to the DLT
 *     immediately. This config retries everything uniformly for simplicity;
 *     a fuller build would add addNotRetryableExceptions() for
 *     JsonProcessingException / IllegalArgumentException so those skip
 *     straight to the DLT instead of wasting 3 attempts.
 *   - Transient failures (DB blip, brief network issue) -> DO benefit from
 *     the backoff-and-retry here.
 *
 * Recovery from the DLT is a manual, deliberate step (see README) - the DLT
 * is a holding area for a human/ops process to inspect and replay after
 * fixing whatever caused the failure, not something auto-replayed, since
 * auto-replaying a poison message would just loop it back to the DLT again.
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            KafkaTemplate<String, String> kafkaTemplate, LedgerMetrics metrics) {

        var consumerFactory = new DefaultKafkaConsumerFactory<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        ));

        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> {
                    metrics.dltMessage();
                    return new org.apache.kafka.common.TopicPartition(KafkaTopicConfig.ACCOUNT_EVENTS_DLT, 0);
                });

        var backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxAttempts(3);

        var errorHandler = new DefaultErrorHandler(recoverer, backOff);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
