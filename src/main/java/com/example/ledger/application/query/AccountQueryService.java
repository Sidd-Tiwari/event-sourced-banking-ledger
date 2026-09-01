package com.example.ledger.application.query;

import com.example.ledger.domain.account.AccountDomainException;
import com.example.ledger.domain.account.AccountState;
import com.example.ledger.infrastructure.persistence.AggregateRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * getCurrentState() and getStateAsOf() both replay the FULL event stream
 * every call. That's correct and fine for a portfolio demo at a few
 * thousand events, but it is exactly the bottleneck the balance projection
 * and snapshots (later commits) exist to remove.
 */
@Service
public class AccountQueryService {

    private final AggregateRepository aggregateRepository;

    public AccountQueryService(AggregateRepository aggregateRepository) {
        this.aggregateRepository = aggregateRepository;
    }

    public AccountState getCurrentState(UUID accountId) {
        AccountState state = aggregateRepository.load(accountId);
        if (!state.exists()) {
            throw new AccountDomainException.AccountNotFound("Account not found: " + accountId);
        }
        return state;
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
