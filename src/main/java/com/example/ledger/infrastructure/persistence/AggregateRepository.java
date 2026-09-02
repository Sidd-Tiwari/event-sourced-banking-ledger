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
 * Phase 1 note: this always replays from sequence 0. That is correct but
 * will not scale to accounts with hundreds of thousands of events - Phase 3
 * introduces snapshotting (load nearest snapshot, then replay only the
 * events after it) without changing this class's public contract, which is
 * the point of keeping decide()/apply() pure: the optimization is purely
 * about *how many* events get folded, never *how* folding works.
 */
@Repository
public class AggregateRepository {

    private final EventStore eventStore;
    private final EventDeserializer deserializer;
    private final SnapshotStore snapshotStore;

    public AggregateRepository(EventStore eventStore, EventDeserializer deserializer, SnapshotStore snapshotStore) {
        this.eventStore = eventStore;
        this.deserializer = deserializer;
        this.snapshotStore = snapshotStore;
    }

    /**
     * Loads the nearest usable snapshot (if any) then replays only the
     * events after it. If no snapshot exists - or one exists but fails to
     * deserialize - this transparently falls back to a full replay from
     * sequence 0. Correctness never depends on the snapshot existing;
     * only replay cost does.
     */
    public AccountState load(UUID accountId) {
        AccountState state = snapshotStore.findLatest(accountId).orElse(AccountState.empty());
        for (StoredEvent stored : eventStore.loadEventsAfter(accountId, state.sequenceNumber(), null)) {
            DomainEvent event = deserializer.deserialize(stored);
            state = Account.apply(state, event);
        }
        return state;
    }

    /**
     * Time-travel: same snapshot-then-replay algorithm as load(), but the
     * snapshot must itself be at or before `asOf`, and replayed events are
     * bounded by `asOf` too. See SnapshotStore.findLatestAsOf for how a
     * snapshot's "as of" time is determined.
     */
    public AccountState loadAsOf(UUID accountId, Instant asOf) {
        AccountState state = snapshotStore.findLatestAsOf(accountId, asOf).orElse(AccountState.empty());
        for (StoredEvent stored : eventStore.loadEventsAfter(accountId, state.sequenceNumber(), asOf)) {
            DomainEvent event = deserializer.deserialize(stored);
            state = Account.apply(state, event);
        }
        return state;
    }
}
