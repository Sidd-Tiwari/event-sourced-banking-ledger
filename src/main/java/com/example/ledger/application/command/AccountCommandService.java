package com.example.ledger.application.command;

import com.example.ledger.domain.account.Account;
import com.example.ledger.domain.account.AccountDomainException;
import com.example.ledger.domain.account.AccountState;
import com.example.ledger.domain.account.commands.Commands.DepositFundsCommand;
import com.example.ledger.domain.account.commands.Commands.FreezeAccountCommand;
import com.example.ledger.domain.account.commands.Commands.OpenAccountCommand;
import com.example.ledger.domain.account.commands.Commands.WithdrawFundsCommand;
import com.example.ledger.domain.account.events.DomainEvent;
import com.example.ledger.infrastructure.observability.LedgerMetrics;
import com.example.ledger.infrastructure.persistence.AggregateRepository;
import com.example.ledger.infrastructure.persistence.ConcurrentModificationException;
import com.example.ledger.infrastructure.persistence.EventMetadata;
import com.example.ledger.infrastructure.persistence.EventStore;
import com.example.ledger.infrastructure.persistence.SnapshotStore;
import com.example.ledger.infrastructure.persistence.StoredEvent;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Every public method here follows the same shape:
 *   1. load current state (replays events, or snapshot+replay from Phase 3)
 *   2. Account.decide(state, command) -> event   (may throw a business exception)
 *   3. EventStore.append(...)                    (may throw ConcurrentModificationException;
 *                                                  also atomically writes the outbox row - Phase 2)
 *   4. maybeSnapshot(...)                        (Phase 3, every SNAPSHOT_INTERVAL events)
 *
 * On a concurrency conflict we retry from step 1 - re-loading picks up
 * whatever the concurrent writer just committed, and re-running decide()
 * re-validates business rules (e.g. balance) against the NEW state rather
 * than blindly reapplying stale intent. This is what makes the retry safe
 * for withdrawals, not just deposits (see ConcurrentModificationException
 * javadoc for why that distinction matters).
 */
@Service
public class AccountCommandService {

    private static final String AGGREGATE_TYPE = "ACCOUNT";
    private static final int MAX_RETRIES = 3;

    /**
     * Snapshot every N events. Chosen as a fixed constant for this portfolio
     * project; a production system would tune this based on the measured
     * replay-cost-vs-snapshot-storage trade-off (see README Phase 3 notes)
     * rather than a guessed value.
     */
    private static final long SNAPSHOT_INTERVAL = 50;

    private final AggregateRepository aggregateRepository;
    private final EventStore eventStore;
    private final SnapshotStore snapshotStore;
    private final LedgerMetrics metrics;

    public AccountCommandService(AggregateRepository aggregateRepository, EventStore eventStore,
                                  SnapshotStore snapshotStore, LedgerMetrics metrics) {
        this.aggregateRepository = aggregateRepository;
        this.eventStore = eventStore;
        this.snapshotStore = snapshotStore;
        this.metrics = metrics;
    }

    public UUID openAccount(OpenAccountCommand cmd, EventMetadata metadata) {
        try {
            AccountState priorState = AccountState.empty();
            DomainEvent event = Account.decide(priorState, cmd);
            var aggregateId = ((com.example.ledger.domain.account.events.AccountOpened) event).accountId();
            StoredEvent stored = metrics.recordAppend(() ->
                    eventStore.append(aggregateId, AGGREGATE_TYPE, 0L, event.eventType(), 1, event, metadata));
            maybeSnapshot(priorState, event, stored);
            metrics.commandProcessed();
            return aggregateId;
        } catch (RuntimeException e) {
            metrics.commandFailed(e.getClass().getSimpleName());
            throw e;
        }
    }

    public void deposit(DepositFundsCommand cmd, EventMetadata metadata) {
        withRetry(cmd.accountId(), state -> {
            DomainEvent event = Account.decide(state, cmd);
            StoredEvent stored = metrics.recordAppend(() -> eventStore.append(cmd.accountId(), AGGREGATE_TYPE,
                    state.sequenceNumber(), event.eventType(), 1, event, metadata));
            maybeSnapshot(state, event, stored);
        });
    }

    public void withdraw(WithdrawFundsCommand cmd, EventMetadata metadata) {
        withRetry(cmd.accountId(), state -> {
            DomainEvent event = Account.decide(state, cmd);
            StoredEvent stored = metrics.recordAppend(() -> eventStore.append(cmd.accountId(), AGGREGATE_TYPE,
                    state.sequenceNumber(), event.eventType(), 1, event, metadata));
            maybeSnapshot(state, event, stored);
        });
    }

    public void freeze(FreezeAccountCommand cmd, EventMetadata metadata) {
        withRetry(cmd.accountId(), state -> {
            DomainEvent event = Account.decide(state, cmd);
            StoredEvent stored = metrics.recordAppend(() -> eventStore.append(cmd.accountId(), AGGREGATE_TYPE,
                    state.sequenceNumber(), event.eventType(), 1, event, metadata));
            maybeSnapshot(state, event, stored);
        });
    }

    private void withRetry(UUID accountId, java.util.function.Consumer<AccountState> attempt) {
        int attempts = 0;
        while (true) {
            AccountState state = aggregateRepository.load(accountId);
            try {
                attempt.accept(state);
                metrics.commandProcessed();
                return;
            } catch (ConcurrentModificationException e) {
                attempts++;
                if (attempts >= MAX_RETRIES) {
                    metrics.commandFailed("ConcurrentModificationException");
                    throw e;
                }
                // loop again: reload fresh state and re-run decide() against it
            } catch (AccountDomainException e) {
                metrics.commandFailed(e.getClass().getSimpleName());
                throw e;
            }
        }
    }

    /**
     * Folds the just-appended event onto the state we already had in hand
     * (no extra DB replay needed) and persists a snapshot every
     * SNAPSHOT_INTERVAL events. Snapshot failures are swallowed here
     * deliberately - see SnapshotStore javadoc: a failed snapshot must never
     * fail the underlying financial command that triggered it.
     */
    private void maybeSnapshot(AccountState priorState, DomainEvent event, StoredEvent stored) {
        AccountState newState = Account.apply(priorState, event);
        if (newState.sequenceNumber() % SNAPSHOT_INTERVAL == 0) {
            try {
                snapshotStore.save(newState, stored.createdAt());
                metrics.snapshotCreated();
            } catch (SnapshotStore.SnapshotWriteFailed e) {
                // Logged inside a real logger in a fuller build; swallowed
                // here so a snapshotting hiccup never rolls back a deposit.
            }
        }
    }
}
