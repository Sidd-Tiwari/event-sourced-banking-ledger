package com.example.ledger.domain.account;

import com.example.ledger.domain.account.commands.Commands.DepositFundsCommand;
import com.example.ledger.domain.account.commands.Commands.FreezeAccountCommand;
import com.example.ledger.domain.account.commands.Commands.OpenAccountCommand;
import com.example.ledger.domain.account.commands.Commands.WithdrawFundsCommand;
import com.example.ledger.domain.account.events.AccountFrozen;
import com.example.ledger.domain.account.events.AccountOpened;
import com.example.ledger.domain.account.events.DomainEvent;
import com.example.ledger.domain.account.events.FundsDeposited;
import com.example.ledger.domain.account.events.FundsWithdrawn;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The Account aggregate contains ONLY behaviour - no persistence, no Spring,
 * no JSON. This is deliberate: business rules must be testable with plain
 * JUnit against in-memory events, independent of Postgres/Kafka/HTTP.
 *
 * Two responsibilities, kept strictly separate:
 *
 *   decide(state, command) -> DomainEvent     // validates intent, may throw
 *   apply(state, event)    -> AccountState    // folds a fact, cannot fail
 *
 * decide() is where business rules live (insufficient funds, frozen account).
 * apply() must be a total, side-effect-free function - it is called both
 * when handling a live command AND when replaying history for a time-travel
 * query, so it must never reject an event once it has been appended.
 */
public final class Account {

    private Account() {}

    // ---- decide: Command + current state -> new Event(s) ----

    public static DomainEvent decide(AccountState state, OpenAccountCommand cmd) {
        if (state.exists()) {
            throw new AccountDomainException.AccountAlreadyExists(
                    "Account already exists and cannot be re-opened");
        }
        return new AccountOpened(UUID.randomUUID(), cmd.customerId(), cmd.currency());
    }

    public static DomainEvent decide(AccountState state, DepositFundsCommand cmd) {
        requireExists(state);
        requirePositiveAmount(cmd.amount());
        if (state.isFrozen()) {
            throw new AccountDomainException.AccountFrozenException(
                    "Cannot deposit into a frozen account: " + state.accountId());
        }
        return new FundsDeposited(state.accountId(), cmd.amount(), cmd.referenceId());
    }

    public static DomainEvent decide(AccountState state, WithdrawFundsCommand cmd) {
        requireExists(state);
        requirePositiveAmount(cmd.amount());
        if (state.isFrozen()) {
            throw new AccountDomainException.AccountFrozenException(
                    "Cannot withdraw from a frozen account: " + state.accountId());
        }
        if (state.balance().compareTo(cmd.amount()) < 0) {
            throw new AccountDomainException.InsufficientFunds(
                    "Balance " + state.balance() + " is less than requested withdrawal " + cmd.amount());
        }
        return new FundsWithdrawn(state.accountId(), cmd.amount(), cmd.referenceId());
    }

    public static DomainEvent decide(AccountState state, FreezeAccountCommand cmd) {
        requireExists(state);
        return new AccountFrozen(state.accountId(), cmd.reason());
    }

    // ---- apply: current state + Event -> new state (pure fold, never rejects) ----

    public static AccountState apply(AccountState state, DomainEvent event) {
        return switch (event) {
            case AccountOpened e -> new AccountState(
                    e.accountId(), e.customerId(), e.currency(),
                    BigDecimal.ZERO, AccountStatus.ACTIVE,
                    state.sequenceNumber() + 1, Instant.now(), Instant.now());

            case FundsDeposited e -> new AccountState(
                    state.accountId(), state.customerId(), state.currency(),
                    state.balance().add(e.amount()), state.status(),
                    state.sequenceNumber() + 1, state.createdAt(), Instant.now());

            case FundsWithdrawn e -> new AccountState(
                    state.accountId(), state.customerId(), state.currency(),
                    state.balance().subtract(e.amount()), state.status(),
                    state.sequenceNumber() + 1, state.createdAt(), Instant.now());

            case AccountFrozen e -> new AccountState(
                    state.accountId(), state.customerId(), state.currency(),
                    state.balance(), AccountStatus.FROZEN,
                    state.sequenceNumber() + 1, state.createdAt(), Instant.now());
        };
    }

    private static void requireExists(AccountState state) {
        if (!state.exists()) {
            throw new AccountDomainException.AccountNotFound("Account does not exist");
        }
    }

    private static void requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AccountDomainException.InvalidAmount(
                    "Amount must be strictly positive, got: " + amount);
        }
    }
}
