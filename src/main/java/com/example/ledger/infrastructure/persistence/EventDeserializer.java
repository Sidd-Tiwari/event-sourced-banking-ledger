package com.example.ledger.infrastructure.persistence;

import com.example.ledger.domain.account.events.AccountFrozen;
import com.example.ledger.domain.account.events.AccountOpened;
import com.example.ledger.domain.account.events.DomainEvent;
import com.example.ledger.domain.account.events.FundsDeposited;
import com.example.ledger.domain.account.events.FundsWithdrawn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Maps event_type (a plain string in the DB) back to a concrete DomainEvent
 * class. This is intentionally a single, explicit switch statement rather
 * than reflection/generic type registries - it is the one place you touch
 * when introducing a new event type or a new version of an existing one
 * (see master prompt section 27, event versioning - e.g. a future
 * "FundsDeposited_v2" would get its own case here plus an upcasting step).
 */
@Component
public class EventDeserializer {

    private final ObjectMapper objectMapper;

    public EventDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DomainEvent deserialize(StoredEvent stored) {
        try {
            return switch (stored.eventType()) {
                case "AccountOpened" -> objectMapper.readValue(stored.payloadJson(), AccountOpened.class);
                case "FundsDeposited" -> objectMapper.readValue(stored.payloadJson(), FundsDeposited.class);
                case "FundsWithdrawn" -> objectMapper.readValue(stored.payloadJson(), FundsWithdrawn.class);
                case "AccountFrozen" -> objectMapper.readValue(stored.payloadJson(), AccountFrozen.class);
                default -> throw new IllegalStateException(
                        "Unknown event type in event store: " + stored.eventType()
                        + " (eventVersion=" + stored.eventVersion() + "). "
                        + "This means either data corruption or a newer event schema "
                        + "than this application version knows how to read.");
            };
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize event " + stored.eventId(), e);
        }
    }
}
