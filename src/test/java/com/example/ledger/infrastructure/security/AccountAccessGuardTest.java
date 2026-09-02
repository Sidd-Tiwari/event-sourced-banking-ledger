package com.example.ledger.infrastructure.security;

import com.example.ledger.application.query.AccountQueryService;
import com.example.ledger.domain.account.AccountDomainException;
import com.example.ledger.domain.account.AccountState;
import com.example.ledger.domain.account.AccountStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit test with mocked collaborators - no Spring context, no DB.
 * This is the exact scenario from the design brief: "a customer must never
 * be able to access another customer's account simply by changing
 * /accounts/{id}".
 */
class AccountAccessGuardTest {

    private final AccountQueryService queryService = mock(AccountQueryService.class);
    private final AccountAccessGuard guard = new AccountAccessGuard(queryService);

    @Test
    void customer_can_access_their_own_account() {
        UUID accountId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(queryService.getCurrentState(accountId)).thenReturn(accountState(accountId, customerId));

        Authentication auth = authAs(new JwtService.AuthenticatedPrincipal("alice", Role.CUSTOMER, customerId));

        assertThat(guard.canAccess(accountId, auth)).isTrue();
    }

    @Test
    void customer_cannot_access_another_customers_account() {
        UUID accountId = UUID.randomUUID();
        UUID actualOwner = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        when(queryService.getCurrentState(accountId)).thenReturn(accountState(accountId, actualOwner));

        Authentication auth = authAs(new JwtService.AuthenticatedPrincipal("mallory", Role.CUSTOMER, attacker));

        assertThat(guard.canAccess(accountId, auth)).isFalse();
    }

    @Test
    void teller_can_access_any_account() {
        UUID accountId = UUID.randomUUID();
        Authentication auth = authAs(new JwtService.AuthenticatedPrincipal("teller1", Role.TELLER, null));

        assertThat(guard.canAccess(accountId, auth)).isTrue();
    }

    @Test
    void nonexistent_account_is_denied_not_errored() {
        UUID accountId = UUID.randomUUID();
        when(queryService.getCurrentState(accountId))
                .thenThrow(new AccountDomainException.AccountNotFound("not found"));

        Authentication auth = authAs(new JwtService.AuthenticatedPrincipal("alice", Role.CUSTOMER, UUID.randomUUID()));

        assertThat(guard.canAccess(accountId, auth)).isFalse();
    }

    private Authentication authAs(JwtService.AuthenticatedPrincipal principal) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(principal);
        return auth;
    }

    private AccountState accountState(UUID accountId, UUID customerId) {
        return new AccountState(accountId, customerId, "INR", BigDecimal.TEN,
                AccountStatus.ACTIVE, 1L, Instant.now(), Instant.now());
    }
}
