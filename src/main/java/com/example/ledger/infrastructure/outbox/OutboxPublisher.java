package com.example.ledger.infrastructure.outbox;

import com.example.ledger.infrastructure.kafka.KafkaTopicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polling-based outbox publisher (as opposed to CDC/Debezium, which reads the
 * Postgres WAL directly). Polling is simpler to run and reason about for a
 * portfolio project; CDC would reduce publish latency and DB load at scale
 * and would be the natural upgrade path, not a rewrite - the outbox TABLE
 * shape doesn't change, only what reads it.
 *
 * Delivery semantics: at-least-once. If the app crashes after send() succeeds
 * but before markPublished() commits, the same row gets resent on the next
 * poll. This is why BalanceProjectionConsumer must check last_event_sequence
 * before applying - the outbox does not (and cannot, without distributed
 * transactions) guarantee exactly-once on its own.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    public void publishPending() {
        List<OutboxRepository.OutboxRow> rows = outboxRepository.findUnpublished(BATCH_SIZE);
        for (OutboxRepository.OutboxRow row : rows) {
            try {
                // Keyed by aggregateId -> Kafka guarantees ordering within the
                // partition that key hashes to, which is all a single account needs.
                kafkaTemplate.send(KafkaTopicConfig.ACCOUNT_EVENTS_TOPIC,
                                row.aggregateId().toString(),
                                envelope(row))
                        .get(); // synchronous for simplicity/demo determinism; see README for async trade-off note
                outboxRepository.markPublished(row.id());
            } catch (Exception e) {
                outboxRepository.incrementAttempts(row.id());
                log.warn("Failed to publish outbox row {} (attempt {}): {}",
                        row.id(), row.publishAttempts() + 1, e.getMessage());
                // Deliberately no exception rethrow: one failed row shouldn't
                // block the rest of the batch. It stays unpublished and is
                // retried on the next scheduled poll.
            }
        }
    }

    private String envelope(OutboxRepository.OutboxRow row) {
        // Wrap event_type alongside the payload so the consumer can dispatch
        // without a second DB lookup. Kept as a hand-built JSON string here
        // to avoid a second Jackson dependency path; a real system would use
        // a shared serialization module between producer/consumer.
        return "{\"eventId\":\"" + row.eventId() + "\","
                + "\"aggregateId\":\"" + row.aggregateId() + "\","
                + "\"sequenceNumber\":" + row.sequenceNumber() + ","
                + "\"eventType\":\"" + row.eventType() + "\","
                + "\"payload\":" + row.payloadJson() + ","
                + "\"metadata\":" + (row.metadataJson() == null ? "null" : row.metadataJson()) + "}";
    }
}
