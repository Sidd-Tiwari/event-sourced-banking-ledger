package com.example.ledger.infrastructure.persistence;

import com.example.ledger.domain.account.Account;
import com.example.ledger.domain.account.AccountState;
import com.example.ledger.domain.account.events.DomainEvent;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Reconstructs AccountState by replaying events from the event store.
 *
 * This always replays from sequence 0. That is correct but will not scale
 * to accounts with hundreds of thousands of events - a later commit
 * introduces snapshotting (load nearest snapshot, then replay only the
 * events after it) without changing this class's public contract, which is
 * the point of keeping decide()/apply() pure: the optimization is purely
 * about *how many* events get folded, never *how* folding works.
 */
@Repository
public class AggregateRepository {

    private final EventStore eventStore;
    private final EventDeserializer deserializer;

    public AggregateRepository(EventStore eventStore, EventDeserializer deserializer) {
        this.eventStore = eventStore;
        this.deserializer = deserializer;
    }

    public AccountState load(UUID accountId) {
        AccountState state = AccountState.empty();
        for (StoredEvent stored : eventStore.loadEvents(accountId)) {
            DomainEvent event = deserializer.deserialize(stored);
            state = Account.apply(state, event);
        }
        return state;
    }

    /** Reconstructs state as of a specific instant - the time-travel primitive. */
    public AccountState loadAsOf(UUID accountId, Instant asOf) {
        AccountState state = AccountState.empty();
        for (StoredEvent stored : eventStore.loadEventsAfter(accountId, 0L, asOf)) {
            DomainEvent event = deserializer.deserialize(stored);
            state = Account.apply(state, event);
        }
        return state;
    }
}
