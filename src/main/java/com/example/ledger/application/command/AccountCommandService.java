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
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Every public method here follows the same shape:
 *   1. load current state (replays events)
 *   2. Account.decide(state, command) -> event   (may throw a business exception)
 *   3. EventStore.append(...)                    (may throw ConcurrentModificationException)
 *
 * On a concurrency conflict we retry from step 1 - re-loading picks up
 * whatever the concurrent writer just committed, and re-running decide()
 * re-validates business rules (e.g. balance) against the NEW state rather
 * than blindly reapplying stale intent. This is what makes the retry safe
 * for withdrawals, not just deposits.
 */
@Service
public class AccountCommandService {

    private static final String AGGREGATE_TYPE = "ACCOUNT";
    private static final int MAX_RETRIES = 3;

    private final AggregateRepository aggregateRepository;
    private final EventStore eventStore;

    public AccountCommandService(AggregateRepository aggregateRepository, EventStore eventStore) {
        this.aggregateRepository = aggregateRepository;
        this.eventStore = eventStore;
    }

    public UUID openAccount(OpenAccountCommand cmd, EventMetadata metadata) {
        AccountState state = AccountState.empty();
        DomainEvent event = Account.decide(state, cmd);
        var aggregateId = ((com.example.ledger.domain.account.events.AccountOpened) event).accountId();
        eventStore.append(aggregateId, AGGREGATE_TYPE, 0L, event.eventType(), 1, event, metadata);
        return aggregateId;
    }

    public void deposit(DepositFundsCommand cmd, EventMetadata metadata) {
        withRetry(cmd.accountId(), state -> {
            DomainEvent event = Account.decide(state, cmd);
            eventStore.append(cmd.accountId(), AGGREGATE_TYPE, state.sequenceNumber(),
                    event.eventType(), 1, event, metadata);
        });
    }

    public void withdraw(WithdrawFundsCommand cmd, EventMetadata metadata) {
        withRetry(cmd.accountId(), state -> {
            DomainEvent event = Account.decide(state, cmd);
            eventStore.append(cmd.accountId(), AGGREGATE_TYPE, state.sequenceNumber(),
                    event.eventType(), 1, event, metadata);
        });
    }

    public void freeze(FreezeAccountCommand cmd, EventMetadata metadata) {
        withRetry(cmd.accountId(), state -> {
            DomainEvent event = Account.decide(state, cmd);
            eventStore.append(cmd.accountId(), AGGREGATE_TYPE, state.sequenceNumber(),
                    event.eventType(), 1, event, metadata);
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
}
