package com.example.ledger.infrastructure.persistence;

import java.util.UUID;

/**
 * Raised when appendEvent's INSERT hits the UNIQUE(aggregate_id, sequence_number)
 * constraint - i.e. another request already wrote that sequence number first.
 *
 * IMPORTANT (see master prompt section 6): this constraint catches the
 * *storage-level* race, but the caller is still responsible for deciding
 * what to do about it. Two realistic outcomes for a client hitting this:
 *
 *   1. Retry: re-load the aggregate (fresh sequence number), re-run decide()
 *      against the new state, and re-attempt the append. Safe for commands
 *      whose business rules don't depend on "did MY specific version win" -
 *      e.g. two concurrent deposits can both safely retry and both succeed.
 *   2. Reject outright: for a withdrawal, blindly retrying without re-checking
 *      the (now different) balance could let two withdrawals overdraw an
 *      account if the retry doesn't re-validate funds - so the retry MUST
 *      go through decide() again on the freshly reloaded state, not just
 *      re-append the same event with a bumped sequence number.
 *
 * This is why the command handler in this project always reloads + re-decides
 * on retry rather than "just incrementing and re-inserting".
 */
public class ConcurrentModificationException extends RuntimeException {
    public ConcurrentModificationException(UUID aggregateId, long expectedSequenceNumber) {
        super("Account " + aggregateId + " was modified concurrently - expected sequence "
                + expectedSequenceNumber + " is stale. Reload and retry.");
    }
}
