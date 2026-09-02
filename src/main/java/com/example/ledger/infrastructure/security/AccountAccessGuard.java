package com.example.ledger.infrastructure.security;

import com.example.ledger.application.query.AccountQueryService;
import com.example.ledger.domain.account.AccountDomainException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * This is the piece that stops a CUSTOMER from reading another customer's
 * account just by changing the {id} in the URL (IDOR - see design brief
 * section 15). Controller-level @PreAuthorize role checks alone CANNOT catch
 * this, because "is this a CUSTOMER" and "does this CUSTOMER own THIS
 * specific account" are two different questions - the second requires
 * looking up the account.
 *
 * TELLER and ADMIN bypass the ownership check entirely in this simplified
 * model (real bank authorization would also scope TELLER access to their
 * assigned branch/customer list - out of scope here, called out explicitly
 * rather than pretending it's handled).
 */
@Component
public class AccountAccessGuard {

    private final AccountQueryService queryService;

    public AccountAccessGuard(AccountQueryService queryService) {
        this.queryService = queryService;
    }

    public boolean canAccess(UUID accountId, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof JwtService.AuthenticatedPrincipal principal)) {
            return false;
        }
        if (principal.role() == Role.TELLER || principal.role() == Role.ADMIN) {
            return true;
        }
        if (principal.role() != Role.CUSTOMER) {
            return false; // AUDITOR goes through a separate @PreAuthorize("hasRole('AUDITOR')") for history endpoints
        }
        try {
            UUID owningCustomerId = queryService.getCurrentState(accountId).customerId();
            return owningCustomerId.equals(principal.customerId());
        } catch (AccountDomainException.AccountNotFound e) {
            return false;
        }
    }
}
