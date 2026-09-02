package com.example.ledger.infrastructure.persistence;

import com.example.ledger.domain.account.AccountState;
import com.example.ledger.domain.account.events.AccountOpened;
import com.example.ledger.infrastructure.observability.LedgerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is the test that actually proves the concurrency design works, not
 * just that it compiles: two threads race to append sequence_number=2 for
 * the same account, and exactly one of them must succeed - proving the
 * UNIQUE(aggregate_id, sequence_number) constraint does what
 * EventStore/ConcurrentModificationException's javadoc claims it does.
 *
 * NOTE ON EXECUTION: this test requires Docker to pull postgres:16-alpine
 * via Testcontainers. It could NOT be run inside the sandbox that produced
 * this codebase (no Docker/network access to image registries there) - run
 * it locally with `./mvnw test -Dtest=EventStoreConcurrencyIT` to verify.
 */
@Testcontainers
class EventStoreConcurrencyIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_test")
            .withUsername("test")
            .withPassword("test");

    private EventStore eventStore;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();

        jdbc = new JdbcTemplate(ds);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var outboxRepository = new com.example.ledger.infrastructure.outbox.OutboxRepository(jdbc);
        eventStore = new EventStore(jdbc, objectMapper, outboxRepository);
    }

    @Test
    void two_concurrent_appends_at_the_same_expected_sequence_number_only_one_succeeds() throws InterruptedException {
        UUID accountId = UUID.randomUUID();
        // Sequence 1: open the account first (both threads will race for sequence 2).
        eventStore.append(accountId, "ACCOUNT", 0L, "AccountOpened", 1,
                new AccountOpened(accountId, UUID.randomUUID(), "INR"), EventMetadata.system());

        int threadCount = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    eventStore.append(accountId, "ACCOUNT", 1L, "FundsDeposited", 1,
                            java.util.Map.of("accountId", accountId, "amount", "10.00", "referenceId", "race"),
                            EventMetadata.system());
                    successes.incrementAndGet();
                } catch (ConcurrentModificationException e) {
                    conflicts.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        // Exactly one thread should have won the race for sequence_number=2;
        // all others must have been rejected as concurrent modifications.
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threadCount - 1);

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_store WHERE aggregate_id = ?", Integer.class, accountId);
        assertThat(rowCount).isEqualTo(2); // AccountOpened + exactly one FundsDeposited
    }
}
