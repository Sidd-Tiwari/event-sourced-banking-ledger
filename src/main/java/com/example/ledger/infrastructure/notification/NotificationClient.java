package com.example.ledger.infrastructure.notification;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Deliberately placed around the NOTIFICATION call only, not around the
 * event store or database calls (see design brief section 17: "do not
 * blindly put circuit breakers around local database calls" - Resilience4j
 * protects against slow/flaky NETWORK dependencies, and a local Postgres
 * transaction failing means something is fundamentally broken, not
 * "transiently slow", so retrying it blindly would be wrong).
 *
 * Call ordering that matters: FundsDeposited is appended to the event store
 * and committed FIRST (see AccountCommandService). This notification is
 * fired asynchronously AFTER that commit - so even if the circuit is open
 * and every retry is exhausted, the deposit itself has already succeeded.
 * The fallback here does nothing but log; it must never throw back into
 * the caller's transaction.
 */
@Component
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final NotificationService notificationService;

    public NotificationClient(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @CircuitBreaker(name = "notificationService", fallbackMethod = "fallback")
    @Retry(name = "notificationService", fallbackMethod = "fallback")
    @TimeLimiter(name = "notificationService", fallbackMethod = "fallback")
    public CompletableFuture<Void> notifyAsync(UUID accountId, String message) {
        return CompletableFuture.runAsync(() -> notificationService.send(accountId, message));
    }

    /**
     * Signature must match the protected method's return type wrapped in the
     * exception, per Resilience4j convention: same args + Throwable last.
     * Deliberately swallows everything - a failed notification is a
     * "customer doesn't get an SMS", not a system failure.
     */
    private CompletableFuture<Void> fallback(UUID accountId, String message, Throwable t) {
        log.warn("Notification suppressed for account {} after retries/circuit-open: {}", accountId, t.getMessage());
        return CompletableFuture.completedFuture(null);
    }
}
