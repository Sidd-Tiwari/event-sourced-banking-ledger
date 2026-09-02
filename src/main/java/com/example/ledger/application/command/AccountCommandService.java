package com.example.ledger.application.command;

import com.example.ledger.domain.account.Account;
import com.example.ledger.domain.account.AccountState;
import com.example.ledger.domain.account.commands.Commands.DepositFundsCommand;
import com.example.ledger.domain.account.commands.Commands.FreezeAccountCommand;
import com.example.ledger.domain.account.commands.Commands.OpenAccountCommand;
import com.example.ledger.domain.account.commands.Commands.WithdrawFundsCommand;
import com.example.ledger.domain.account.events.DomainEvent;
import com.example.ledger.infrastructure.persistence.AggregateRepository;
import com.example.ledger.infrastructure.persistence.ConcurrentModificationException;
import com.example.ledger.infrastructure.persistence.EventMetadata;
import com.example.ledger.infrastructure.persistence.EventStore;
import com.example.ledger.infrastructure.persistence.SnapshotStore;
import com.example.ledger.infrastructure.persistence.StoredEvent;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AccountCommandService {

    private static final String AGGREGATE_TYPE = "ACCOUNT";
    private static final int MAX_RETRIES = 3;

    /**
     * Snapshot every N events. Fixed constant for this portfolio project;
     * a production system would tune this from measured replay-cost-vs-
     * snapshot-storage trade-offs rather than a guessed value.
     */
    private static final long SNAPSHOT_INTERVAL = 50;

    private final AggregateRepository aggregateRepository;
    private final EventStore eventStore;
    private final SnapshotStore snapshotStore;

    public AccountCommandService(AggregateRepository aggregateRepository, EventStore eventStore,
                                  SnapshotStore snapshotStore) {
        this.aggregateRepository = aggregateRepository;
        this.eventStore = eventStore;
        this.snapshotStore = snapshotStore;
    }

    public UUID openAccount(OpenAccountCommand cmd, EventMetadata metadata) {
        AccountState priorState = AccountState.empty();
        DomainEvent event = Account.decide(priorState, cmd);
        var aggregateId = ((com.example.ledger.domain.account.events.AccountOpened) event).accountId();
        StoredEvent stored = eventStore.append(aggregateId, AGGREGATE_TYPE, 0L, event.eventType(), 1, event, metadata);
        maybeSnapshot(priorState, event, stored);
        return aggregateId;
    }

    public void deposit(DepositFundsCommand cmd, EventMetadata metadata) {
        withRetry(cmd.accountId(), state -> {
            DomainEvent event = Account.decide(state, cmd);
            StoredEvent stored = eventStore.append(cmd.accountId(), AGGREGATE_TYPE, state.sequenceNumber(),
                    event.eventType(), 1, event, metadata);
            maybeSnapshot(state, event, stored);
        });
    }

    public void withdraw(WithdrawFundsCommand cmd, EventMetadata metadata) {
        withRetry(cmd.accountId(), state -> {
            DomainEvent event = Account.decide(state, cmd);
            StoredEvent stored = eventStore.append(cmd.accountId(), AGGREGATE_TYPE, state.sequenceNumber(),
                    event.eventType(), 1, event, metadata);
            maybeSnapshot(state, event, stored);
        });
    }

    public void freeze(FreezeAccountCommand cmd, EventMetadata metadata) {
        withRetry(cmd.accountId(), state -> {
            DomainEvent event = Account.decide(state, cmd);
            StoredEvent stored = eventStore.append(cmd.accountId(), AGGREGATE_TYPE, state.sequenceNumber(),
                    event.eventType(), 1, event, metadata);
            maybeSnapshot(state, event, stored);
        });
    }

    private void withRetry(UUID accountId, java.util.function.Consumer<AccountState> attempt) {
        int attempts = 0;
        while (true) {
            AccountState state = aggregateRepository.load(accountId);
            try {
                attempt.accept(state);
                return;
            } catch (ConcurrentModificationException e) {
                attempts++;
                if (attempts >= MAX_RETRIES) {
                    throw e;
                }
            }
        }
    }

    /**
     * Folds the just-appended event onto the state we already had in hand
     * (no extra DB replay needed) and persists a snapshot every
     * SNAPSHOT_INTERVAL events. Snapshot failures are swallowed here
     * deliberately - a failed snapshot must never fail the underlying
     * financial command that triggered it.
     */
    private void maybeSnapshot(AccountState priorState, DomainEvent event, StoredEvent stored) {
        AccountState newState = Account.apply(priorState, event);
        if (newState.sequenceNumber() % SNAPSHOT_INTERVAL == 0) {
            try {
                snapshotStore.save(newState, stored.createdAt());
            } catch (SnapshotStore.SnapshotWriteFailed e) {
                // Swallowed so a snapshotting hiccup never rolls back a deposit.
            }
        }
    }
}
