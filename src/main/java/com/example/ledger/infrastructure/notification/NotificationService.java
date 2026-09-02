package com.example.ledger.infrastructure.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stands in for a real external notification provider (SMS/email/push).
 * Deliberately flaky - sleeps a random amount and randomly throws - so the
 * Resilience4j wrapper (NotificationClient) has something real to protect
 * against, and so a chaos-style demo ("kill this dependency, watch the
 * circuit breaker open, confirm deposits still succeed") is meaningful.
 */
@Component
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public void send(UUID accountId, String message) {
        int delayMs = ThreadLocalRandom.current().nextInt(50, 400);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (ThreadLocalRandom.current().nextInt(100) < 30) { // ~30% simulated failure rate
            throw new NotificationDeliveryException("Simulated notification provider failure for account " + accountId);
        }
        log.info("Notification sent to account {}: {}", accountId, message);
    }

    public static class NotificationDeliveryException extends RuntimeException {
        public NotificationDeliveryException(String message) {
            super(message);
        }
    }
}
