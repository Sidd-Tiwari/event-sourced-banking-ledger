package com.example.ledger.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The event store is the ONLY place business mutations are durably recorded.
 * Everything else built in later commits (balance projections, snapshots) is
 * a disposable, rebuildable-from-scratch cache of what's in here.
 *
 * Concurrency strategy: the UNIQUE(aggregate_id, sequence_number) constraint
 * on the table does the actual enforcement - append() just translates the
 * resulting DuplicateKeyException into a domain-meaningful
 * ConcurrentModificationException. See that class's javadoc for what the
 * *caller* still has to do about it.
 */
@Repository
public class EventStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EventStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public StoredEvent append(UUID aggregateId,
                               String aggregateType,
                               long expectedSequenceNumber,
                               String eventType,
                               int eventVersion,
                               Object payload,
                               EventMetadata metadata) {

        long newSequenceNumber = expectedSequenceNumber + 1;
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        String payloadJson = writeJson(payload);
        String metadataJson = writeJson(metadata);

        try {
            jdbc.update("""
                    INSERT INTO event_store
                        (event_id, aggregate_id, aggregate_type, sequence_number,
                         event_type, event_version, payload, metadata, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                    """,
                    eventId, aggregateId, aggregateType, newSequenceNumber,
                    eventType, eventVersion, payloadJson, metadataJson, now);
        } catch (DuplicateKeyException e) {
            throw new ConcurrentModificationException(aggregateId, expectedSequenceNumber);
        }

        return new StoredEvent(0L, eventId, aggregateId, aggregateType, newSequenceNumber,
                eventType, eventVersion, payloadJson, metadataJson, now);
    }

    /** All events for an aggregate, in strict sequence order - the full history. */
    public List<StoredEvent> loadEvents(UUID aggregateId) {
        return jdbc.query("""
                SELECT id, event_id, aggregate_id, aggregate_type, sequence_number,
                       event_type, event_version, payload::text, metadata::text, created_at
                FROM event_store
                WHERE aggregate_id = ?
                ORDER BY sequence_number ASC
                """, ROW_MAPPER, aggregateId);
    }

    /**
     * Events after a given sequence number, optionally bounded by a timestamp.
     * asOf == null means "no upper time bound" (normal replay); a non-null
     * asOf powers the time-travel query.
     */
    public List<StoredEvent> loadEventsAfter(UUID aggregateId, long afterSequenceNumber, Instant asOfOrNull) {
        if (asOfOrNull == null) {
            return jdbc.query("""
                    SELECT id, event_id, aggregate_id, aggregate_type, sequence_number,
                           event_type, event_version, payload::text, metadata::text, created_at
                    FROM event_store
                    WHERE aggregate_id = ? AND sequence_number > ?
                    ORDER BY sequence_number ASC
                    """, ROW_MAPPER, aggregateId, afterSequenceNumber);
        }
        return jdbc.query("""
                SELECT id, event_id, aggregate_id, aggregate_type, sequence_number,
                       event_type, event_version, payload::text, metadata::text, created_at
                FROM event_store
                WHERE aggregate_id = ? AND sequence_number > ? AND created_at <= ?
                ORDER BY sequence_number ASC
                """, ROW_MAPPER, aggregateId, afterSequenceNumber, asOfOrNull);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event payload/metadata", e);
        }
    }

    private static final RowMapper<StoredEvent> ROW_MAPPER = (rs, rowNum) -> new StoredEvent(
            rs.getLong("id"),
            UUID.fromString(rs.getString("event_id")),
            UUID.fromString(rs.getString("aggregate_id")),
            rs.getString("aggregate_type"),
            rs.getLong("sequence_number"),
            rs.getString("event_type"),
            rs.getInt("event_version"),
            rs.getString("payload"),
            rs.getString("metadata"),
            rs.getTimestamp("created_at").toInstant()
    );
}
