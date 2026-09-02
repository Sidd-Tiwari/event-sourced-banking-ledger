package com.example.ledger.infrastructure.notification;

import com.example.ledger.infrastructure.kafka.KafkaTopicConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * A DIFFERENT consumer group ("notifications") from BalanceProjectionConsumer
 * ("balance-projection") reading the SAME topic. This is deliberate: Kafka
 * consumer groups are independent, so a notification-path slowdown (circuit
 * breaker open, retries exhausting) cannot create lag on the balance
 * projection's consumption of the same partition - they're fully isolated
 * subscriptions, not a shared thread pool. This is the "bulkhead" pattern
 * applied at the messaging level rather than via a Resilience4j Bulkhead
 * annotation.
 */
@Component
public class NotificationConsumer {

    private final NotificationClient notificationClient;
    private final ObjectMapper objectMapper;

    public NotificationConsumer(NotificationClient notificationClient, ObjectMapper objectMapper) {
        this.notificationClient = notificationClient;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopicConfig.ACCOUNT_EVENTS_TOPIC, groupId = "notifications")
    public void onMessage(String message) throws Exception {
        JsonNode node = objectMapper.readTree(message);
        if (!"FundsDeposited".equals(node.get("eventType").asText())) {
            return;
        }
        UUID accountId = UUID.fromString(node.get("aggregateId").asText());
        String amount = node.get("payload").get("amount").asText();
        // Fire-and-forget from the consumer's perspective: notifyAsync returns
        // a CompletableFuture that resolves via fallback() on failure, so this
        // listener thread returns immediately and the offset commits either way.
        notificationClient.notifyAsync(accountId, "Deposit of " + amount + " received");
    }
}
