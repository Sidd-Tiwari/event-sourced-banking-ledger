package com.example.ledger.domain.account.commands;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Commands express intent and can fail validation (insufficient funds,
 * frozen account, etc). Once validated, a command produces one or more
 * DomainEvents which are facts and cannot be rejected after the fact.
 *
 * Kept as static nested records in one file for Phase 1 brevity; split
 * into separate files if the set grows (e.g. once TransferFunds/Freeze
 * command handlers are fleshed out in Phase 2+).
 */
public final class Commands {

    private Commands() {}

    public record OpenAccountCommand(
            UUID customerId,
            String currency
    ) {}

    public record DepositFundsCommand(
            UUID accountId,
            BigDecimal amount,
            String referenceId,
            long expectedSequenceNumber
    ) {}

    public record WithdrawFundsCommand(
            UUID accountId,
            BigDecimal amount,
            String referenceId,
            long expectedSequenceNumber
    ) {}

    public record FreezeAccountCommand(
            UUID accountId,
            String reason,
            long expectedSequenceNumber
    ) {}
}
