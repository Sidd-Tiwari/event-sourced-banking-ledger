package com.example.ledger.infrastructure.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BalanceProjectionRepository {

    private final JdbcTemplate jdbc;

    public BalanceProjectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> lastAppliedSequence(UUID accountId) {
        return jdbc.query("SELECT last_event_sequence FROM account_balance_view WHERE account_id = ?",
                        rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.<Long>empty(),
                        accountId);
    }

    public void upsertOnOpen(UUID accountId, UUID customerId, String currency, long sequenceNumber) {
        jdbc.update("""
                INSERT INTO account_balance_view (account_id, customer_id, currency, current_balance, status, last_event_sequence, updated_at)
                VALUES (?, ?, ?, 0, 'ACTIVE', ?, ?)
                ON CONFLICT (account_id) DO NOTHING
                """, accountId, customerId, currency, sequenceNumber, Instant.now());
    }

    public void applyDelta(UUID accountId, BigDecimal delta, long sequenceNumber) {
        jdbc.update("""
                UPDATE account_balance_view
                SET current_balance = current_balance + ?, last_event_sequence = ?, updated_at = ?
                WHERE account_id = ?
                """, delta, sequenceNumber, Instant.now(), accountId);
    }

    public void markFrozen(UUID accountId, long sequenceNumber) {
        jdbc.update("""
                UPDATE account_balance_view
                SET status = 'FROZEN', last_event_sequence = ?, updated_at = ?
                WHERE account_id = ?
                """, sequenceNumber, Instant.now(), accountId);
    }

    public record BalanceRow(UUID accountId, UUID customerId, String currency,
                              BigDecimal balance, String status, long lastEventSequence) {}

    public Optional<BalanceRow> find(UUID accountId) {
        return jdbc.query("""
                SELECT account_id, customer_id, currency, current_balance, status, last_event_sequence
                FROM account_balance_view WHERE account_id = ?
                """, rs -> rs.next() ? Optional.of(new BalanceRow(
                        UUID.fromString(rs.getString("account_id")),
                        UUID.fromString(rs.getString("customer_id")),
                        rs.getString("currency"),
                        rs.getBigDecimal("current_balance"),
                        rs.getString("status"),
                        rs.getLong("last_event_sequence")
                )) : Optional.<BalanceRow>empty(), accountId);
    }
}
