package com.example.ledger.application.query;

import com.example.ledger.domain.account.AccountDomainException;
import com.example.ledger.domain.account.AccountState;
import com.example.ledger.infrastructure.observability.LedgerMetrics;
import com.example.ledger.infrastructure.persistence.AggregateRepository;
import com.example.ledger.infrastructure.projection.BalanceProjectionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 1 note: getCurrentState() and getStateAsOf() both replay the FULL
 * event stream every call. That's correct and fine for a portfolio demo at
 * a few thousand events, but it is exactly the bottleneck the balance
 * projection (Phase 2) and snapshots (Phase 3) exist to remove - current
 * balance lookups should hit account_balance_view, not the event store,
 * once those land. Keeping this naive version first lets you *prove* the
 * before/after performance difference with real benchmark numbers instead
 * of asserting it.
 */
@Service
public class AccountQueryService {

    private final AggregateRepository aggregateRepository;
    private final BalanceProjectionRepository balanceProjectionRepository;
    private final LedgerMetrics metrics;

    public AccountQueryService(AggregateRepository aggregateRepository,
                                BalanceProjectionRepository balanceProjectionRepository,
                                LedgerMetrics metrics) {
        this.aggregateRepository = aggregateRepository;
        this.balanceProjectionRepository = balanceProjectionRepository;
        this.metrics = metrics;
    }

    /**
     * "Current state" for the full account resource still replays the event
     * store directly - it's the safest source when correctness matters more
     * than latency (e.g. right before authorizing a withdrawal).
     */
    public AccountState getCurrentState(UUID accountId) {
        AccountState state = aggregateRepository.load(accountId);
        if (!state.exists()) {
            throw new AccountDomainException.AccountNotFound("Account not found: " + accountId);
        }
        return state;
    }

    /**
     * Fast-path balance lookup: reads the projection (O(1) indexed row) instead
     * of replaying potentially thousands of events. This is the exact query
     * the JMeter benchmark in Phase 7 is meant to compare against
     * getCurrentState() to demonstrate the projection's performance win.
     *
     * Trade-off made explicit: this can be milliseconds-to-seconds stale
     * relative to the event store, since it's updated asynchronously via
     * Kafka. Acceptable for "show me my balance"; NOT acceptable as the
     * authority for authorizing a withdrawal (that goes through decide()
     * against freshly replayed state instead).
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
        AccountState state = metrics.recordTimeTravelQuery(() -> aggregateRepository.loadAsOf(accountId, asOf));
        if (!state.exists()) {
            throw new AccountDomainException.AccountNotFound(
                    "Account " + accountId + " did not exist as of " + asOf);
        }
        return state;
    }
}
