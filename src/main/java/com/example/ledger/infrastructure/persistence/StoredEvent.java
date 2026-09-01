package com.example.ledger.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * Raw persisted row from event_store, before it is deserialized back into
 * a typed DomainEvent. Kept separate from the domain event types so the
 * domain package has zero dependency on JSON/JDBC concerns.
 */
public record StoredEvent(
        long id,
        UUID eventId,
        UUID aggregateId,
        String aggregateType,
        long sequenceNumber,
        String eventType,
        int eventVersion,
        String payloadJson,
        String metadataJson,
        Instant createdAt
) {}
