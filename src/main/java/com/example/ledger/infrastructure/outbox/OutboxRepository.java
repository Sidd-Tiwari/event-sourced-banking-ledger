package com.example.ledger.infrastructure.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Called from EventStore.append() - relies on Spring's default REQUIRED
     * propagation to join the SAME transaction as the event_store insert.
     * If either insert fails, both roll back together; there is no window
     * where an event exists without a corresponding outbox row (or vice versa).
     */
    public void insert(UUID eventId, UUID aggregateId, long sequenceNumber, String eventType,
                        String payloadJson, String metadataJson) {
        jdbc.update("""
                INSERT INTO outbox (event_id, aggregate_id, sequence_number, event_type, payload, metadata, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """,
                eventId, aggregateId, sequenceNumber, eventType, payloadJson, metadataJson, Instant.now());
    }

    public List<OutboxRow> findUnpublished(int limit) {
        return jdbc.query("""
                SELECT id, event_id, aggregate_id, sequence_number, event_type, payload::text, metadata::text, publish_attempts
                FROM outbox
                WHERE published_at IS NULL
                ORDER BY created_at ASC
                LIMIT ?
                """, ROW_MAPPER, limit);
    }

    public void markPublished(long outboxId) {
        jdbc.update("UPDATE outbox SET published_at = ? WHERE id = ?", Instant.now(), outboxId);
    }

    public void incrementAttempts(long outboxId) {
        jdbc.update("UPDATE outbox SET publish_attempts = publish_attempts + 1 WHERE id = ?", outboxId);
    }

    public record OutboxRow(long id, UUID eventId, UUID aggregateId, long sequenceNumber, String eventType,
                             String payloadJson, String metadataJson, int publishAttempts) {}

    private static final RowMapper<OutboxRow> ROW_MAPPER = (rs, rowNum) -> new OutboxRow(
            rs.getLong("id"),
            UUID.fromString(rs.getString("event_id")),
            UUID.fromString(rs.getString("aggregate_id")),
            rs.getLong("sequence_number"),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getString("metadata"),
            rs.getInt("publish_attempts")
    );
}
