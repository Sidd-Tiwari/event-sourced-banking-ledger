package com.example.ledger.domain.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AccountState is NOT persisted as the source of truth - it is always
 * *derived* by folding events onto an empty (or snapshotted) state.
 *
 * It is immutable: every event application produces a brand new
 * AccountState rather than mutating this one. That makes apply() a pure
 * function (state, event) -> newState, which is what makes replay
 * deterministic and testable in isolation without a database.
 */
public record AccountState(
        UUID accountId,
        UUID customerId,
        String currency,
        BigDecimal balance,
        AccountStatus status,
        long sequenceNumber,
        Instant createdAt,
        Instant updatedAt
) {

    /** The "does not exist yet" state, used as the fold seed for a brand new aggregate. */
    public static AccountState empty() {
        return new AccountState(null, null, null, BigDecimal.ZERO, null, 0L, null, null);
    }

    public boolean exists() {
        return accountId != null;
    }

    public boolean isFrozen() {
        return status == AccountStatus.FROZEN;
    }
}
