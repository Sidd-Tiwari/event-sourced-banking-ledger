package com.example.ledger.application.query;

import com.example.ledger.domain.account.AccountDomainException;
import com.example.ledger.domain.account.AccountState;
import com.example.ledger.infrastructure.persistence.AggregateRepository;
import com.example.ledger.infrastructure.projection.BalanceProjectionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class AccountQueryService {

    private final AggregateRepository aggregateRepository;
    private final BalanceProjectionRepository balanceProjectionRepository;

    public AccountQueryService(AggregateRepository aggregateRepository,
                                BalanceProjectionRepository balanceProjectionRepository) {
        this.aggregateRepository = aggregateRepository;
        this.balanceProjectionRepository = balanceProjectionRepository;
    }

    /**
     * "Current state" for the full account resource still replays the event
     * store directly - safest when correctness matters more than latency.
     */
    public AccountState getCurrentState(UUID accountId) {
        AccountState state = aggregateRepository.load(accountId);
        if (!state.exists()) {
            throw new AccountDomainException.AccountNotFound("Account not found: " + accountId);
        }
        return state;
    }

    /**
     * Fast-path balance lookup: reads the projection (O(1) indexed row)
     * instead of replaying potentially thousands of events. Trade-off made
     * explicit: this can be milliseconds-to-seconds stale relative to the
     * event store since it's updated asynchronously via Kafka. Acceptable
     * for "show me my balance"; NOT acceptable as the authority for
     * authorizing a withdrawal (that goes through decide() against freshly
     * replayed state instead).
     */
    public BigDecimal getFastBalance(UUID accountId) {
        return balanceProjectionRepository.find(accountId)
                .map(BalanceProjectionRepository.BalanceRow::balance)
                .orElseThrow(() -> new AccountDomainException.AccountNotFound(
                        "No balance projection yet for account " + accountId
                        + " (it may not have been consumed from Kafka yet)"));
    }

    /** The time-travel query: reconstructs exactly what the account looked like at `asOf`. */
    public AccountState getStateAsOf(UUID accountId, Instant asOf) {
        AccountState state = aggregateRepository.loadAsOf(accountId, asOf);
        if (!state.exists()) {
            throw new AccountDomainException.AccountNotFound(
                    "Account " + accountId + " did not exist as of " + asOf);
        }
        return state;
    }
}
