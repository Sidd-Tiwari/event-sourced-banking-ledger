package com.example.ledger.infrastructure.kafka;

import com.example.ledger.infrastructure.projection.BalanceProjectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Idempotent by construction: before applying an event, we compare its
 * sequenceNumber against account_balance_view.last_event_sequence. Kafka's
 * at-least-once delivery (retries, outbox re-publication, consumer rebalances)
 * means this listener WILL see duplicate deliveries eventually - skipping
 * anything <= the already-applied sequence is what makes that safe.
 *
 * Ordering: within a single account's partition, order is preserved (see
 * KafkaTopicConfig), so "sequence 43 arrives before 42" should not happen
 * in steady state. The out-of-order guard here handles it defensively anyway
 * (e.g. after a partition reassignment) by simply not going backwards -
 * NOT by trying to "replay" the gap, since the projection isn't the source
 * of truth and a gap can always be healed by re-running the projection from
 * the event store if it's ever found to be wrong.
 */
@Component
public class BalanceProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(BalanceProjectionConsumer.class);

    private final BalanceProjectionRepository repository;
    private final ObjectMapper objectMapper;

    public BalanceProjectionConsumer(BalanceProjectionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopicConfig.ACCOUNT_EVENTS_TOPIC, groupId = "balance-projection")
    public void onMessage(String message) {
        JsonNode node;
        try {
            node = objectMapper.readTree(message);
        } catch (Exception e) {
            // Malformed message: not something a retry will fix. Let it flow
            // to the DLT via the error handler (configured in Phase 5) rather
            // than blocking the partition forever.
            throw new IllegalStateException("Malformed Kafka message, cannot parse: " + e.getMessage(), e);
        }

        UUID accountId = UUID.fromString(node.get("aggregateId").asText());
        String eventType = node.get("eventType").asText();
        JsonNode payload = node.get("payload");
        // sequenceNumber lives at the envelope's top level, not inside the
        // domain event payload itself - the aggregate's sequence is a
        // storage/ordering concern (event_store row), not part of the
        // business fact. OutboxPublisher.envelope() is what puts it there.
        long sequenceNumber = node.get("sequenceNumber").asLong();

        switch (eventType) {
            case "AccountOpened" -> {
                UUID customerId = UUID.fromString(payload.get("customerId").asText());
                String currency = payload.get("currency").asText();
                repository.upsertOnOpen(accountId, customerId, currency, sequenceNumber);
            }
            case "FundsDeposited" -> applyIfNewer(accountId, sequenceNumber,
                    () -> repository.applyDelta(accountId, new BigDecimal(payload.get("amount").asText()), sequenceNumber));
            case "FundsWithdrawn" -> applyIfNewer(accountId, sequenceNumber,
                    () -> repository.applyDelta(accountId, new BigDecimal(payload.get("amount").asText()).negate(), sequenceNumber));
            case "AccountFrozen" -> applyIfNewer(accountId, sequenceNumber,
                    () -> repository.markFrozen(accountId, sequenceNumber));
            default -> log.warn("Unknown event type in projection consumer: {}", eventType);
        }
    }

    private void applyIfNewer(UUID accountId, long sequenceNumber, Runnable apply) {
        long lastApplied = repository.lastAppliedSequence(accountId).orElse(0L);
        if (sequenceNumber <= lastApplied) {
            log.debug("Skipping already-applied event for account {} (seq {} <= last applied {})",
                    accountId, sequenceNumber, lastApplied);
            return;
        }
        apply.run();
    }
}
