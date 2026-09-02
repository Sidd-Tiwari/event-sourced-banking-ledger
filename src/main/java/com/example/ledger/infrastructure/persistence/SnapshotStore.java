package com.example.ledger.infrastructure.persistence;

import com.example.ledger.domain.account.AccountState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Snapshots are keyed by (account_id, sequence_number). find*() methods
 * return Optional.empty() on ANY problem - missing row, corrupted JSON,
 * whatever - rather than throwing. AggregateRepository treats "no usable
 * snapshot" as "replay from the beginning", which is always correct, just
 * slower. A corrupted snapshot must never be able to produce a WRONG answer,
 * only a slower one.
 */
@Repository
public class SnapshotStore {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SnapshotStore(org.springframework.jdbc.core.JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void save(AccountState state, Instant eventCreatedAt) {
        try {
            String json = objectMapper.writeValueAsString(state);
            jdbc.update("""
                    INSERT INTO account_snapshots (account_id, sequence_number, state, event_created_at, created_at)
                    VALUES (?, ?, ?::jsonb, ?, ?)
                    ON CONFLICT (account_id, sequence_number) DO NOTHING
                    """, state.accountId(), state.sequenceNumber(), json, eventCreatedAt, Instant.now());
        } catch (Exception e) {
            // Snapshot creation failing must NEVER fail the command that
            // triggered it - it's purely an optimization. Log and move on.
            throw new SnapshotWriteFailed(state.accountId(), state.sequenceNumber(), e);
        }
    }

    /** Latest snapshot for "give me current state" queries (no time bound). */
    public Optional<AccountState> findLatest(UUID accountId) {
        return jdbc.query("""
                SELECT state::text FROM account_snapshots
                WHERE account_id = ?
                ORDER BY sequence_number DESC
                LIMIT 1
                """, this::mapFirstRow, accountId);
    }

    /** Latest snapshot at or before a given instant - the time-travel primitive. */
    public Optional<AccountState> findLatestAsOf(UUID accountId, Instant asOf) {
        return jdbc.query("""
                SELECT state::text FROM account_snapshots
                WHERE account_id = ? AND event_created_at <= ?
                ORDER BY sequence_number DESC
                LIMIT 1
                """, this::mapFirstRow, accountId, asOf);
    }

    private Optional<AccountState> mapFirstRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        if (!rs.next()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(rs.getString(1), AccountState.class));
        } catch (Exception e) {
            // Corrupted/unreadable snapshot JSON - treat exactly like "no snapshot".
            return Optional.empty();
        }
    }

    public static class SnapshotWriteFailed extends RuntimeException {
        public SnapshotWriteFailed(UUID accountId, long seq, Throwable cause) {
            super("Failed to write snapshot for account " + accountId + " at sequence " + seq, cause);
        }
    }
}
