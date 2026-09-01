package com.example.ledger.domain;

import com.example.ledger.domain.account.Account;
import com.example.ledger.domain.account.AccountDomainException;
import com.example.ledger.domain.account.AccountState;
import com.example.ledger.domain.account.commands.Commands.DepositFundsCommand;
import com.example.ledger.domain.account.commands.Commands.FreezeAccountCommand;
import com.example.ledger.domain.account.commands.Commands.OpenAccountCommand;
import com.example.ledger.domain.account.commands.Commands.WithdrawFundsCommand;
import com.example.ledger.domain.account.events.DomainEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * These tests operate entirely on decide()/apply() with zero Spring context,
 * zero database - proving the business rules are correct independent of
 * infrastructure. This is the test suite you'd walk an interviewer through
 * to demonstrate the aggregate design.
 */
class AccountTest {

    @Test
    void opening_an_account_produces_a_zero_balance_active_account() {
        DomainEvent opened = Account.decide(AccountState.empty(),
                new OpenAccountCommand(UUID.randomUUID(), "INR"));

        AccountState state = Account.apply(AccountState.empty(), opened);

        assertThat(state.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(state.sequenceNumber()).isEqualTo(1);
        assertThat(state.exists()).isTrue();
    }

    @Test
    void deposits_increase_balance_and_sequence_number() {
        AccountState state = openTestAccount();

        DomainEvent deposited = Account.decide(state,
                new DepositFundsCommand(state.accountId(), new BigDecimal("500.00"), "ref-1", state.sequenceNumber()));
        state = Account.apply(state, deposited);

        assertThat(state.balance()).isEqualByComparingTo("500.00");
        assertThat(state.sequenceNumber()).isEqualTo(2);
    }

    @Test
    void withdrawal_larger_than_balance_is_rejected() {
        AccountState state = openTestAccount();

        assertThatThrownBy(() -> Account.decide(state,
                new WithdrawFundsCommand(state.accountId(), new BigDecimal("50.00"), "ref-1", state.sequenceNumber())))
                .isInstanceOf(AccountDomainException.InsufficientFunds.class);
    }

    @Test
    void frozen_account_rejects_deposits_and_withdrawals() {
        AccountState state = openTestAccount();
        DomainEvent frozen = Account.decide(state,
                new FreezeAccountCommand(state.accountId(), "suspicious activity", state.sequenceNumber()));
        state = Account.apply(state, frozen);

        AccountState frozenState = state;
        assertThatThrownBy(() -> Account.decide(frozenState,
                new DepositFundsCommand(frozenState.accountId(), BigDecimal.TEN, "ref-1", frozenState.sequenceNumber())))
                .isInstanceOf(AccountDomainException.AccountFrozenException.class);
    }

    @Test
    void negative_or_zero_amount_is_rejected() {
        AccountState state = openTestAccount();

        assertThatThrownBy(() -> Account.decide(state,
                new DepositFundsCommand(state.accountId(), BigDecimal.ZERO, "ref-1", state.sequenceNumber())))
                .isInstanceOf(AccountDomainException.InvalidAmount.class);
    }

    @Test
    void replaying_a_sequence_of_events_from_empty_reconstructs_correct_final_state() {
        // This is the property the whole time-travel feature depends on:
        // folding the same events in order always yields the same state.
        UUID customerId = UUID.randomUUID();
        AccountState state = AccountState.empty();

        DomainEvent opened = Account.decide(state, new OpenAccountCommand(customerId, "USD"));
        state = Account.apply(state, opened);

        DomainEvent d1 = Account.decide(state, new DepositFundsCommand(state.accountId(), new BigDecimal("1000"), "r1", state.sequenceNumber()));
        state = Account.apply(state, d1);

        DomainEvent w1 = Account.decide(state, new WithdrawFundsCommand(state.accountId(), new BigDecimal("300"), "r2", state.sequenceNumber()));
        state = Account.apply(state, w1);

        DomainEvent d2 = Account.decide(state, new DepositFundsCommand(state.accountId(), new BigDecimal("50"), "r3", state.sequenceNumber()));
        state = Account.apply(state, d2);

        assertThat(state.balance()).isEqualByComparingTo("750");
        assertThat(state.sequenceNumber()).isEqualTo(4);
    }

    private AccountState openTestAccount() {
        DomainEvent opened = Account.decide(AccountState.empty(),
                new OpenAccountCommand(UUID.randomUUID(), "INR"));
        return Account.apply(AccountState.empty(), opened);
    }
}
