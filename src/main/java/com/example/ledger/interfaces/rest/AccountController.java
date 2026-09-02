package com.example.ledger.interfaces.rest;

import com.example.ledger.application.command.AccountCommandService;
import com.example.ledger.application.query.AccountQueryService;
import com.example.ledger.domain.account.commands.Commands.DepositFundsCommand;
import com.example.ledger.domain.account.commands.Commands.FreezeAccountCommand;
import com.example.ledger.domain.account.commands.Commands.OpenAccountCommand;
import com.example.ledger.domain.account.commands.Commands.WithdrawFundsCommand;
import com.example.ledger.infrastructure.persistence.EventMetadata;
import com.example.ledger.infrastructure.security.JwtService;
import com.example.ledger.interfaces.rest.dto.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * CQRS split at the API layer: POST endpoints are commands (mutate via the
 * event store), GET endpoints are queries (read derived state). No endpoint
 * here exposes JPA/persistence types - everything crosses the wire as a DTO.
 *
 * Authorization model:
 *   - CUSTOMER, TELLER, ADMIN may open/deposit/withdraw, gated by
 *     AccountAccessGuard.canAccess (object-level: a CUSTOMER only touches
 *     their own account; TELLER/ADMIN bypass ownership in this simplified
 *     model - see AccountAccessGuard javadoc).
 *   - Freezing an account is ADMIN-only (a customer freezing their own
 *     account, or a teller freezing arbitrary accounts, is a business
 *     decision this project doesn't need to model further).
 *   - The time-travel endpoint is AUDITOR-only, full stop - no ownership
 *     bypass, since audit access itself is the sensitive operation being
 *     controlled (see design brief section 14).
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountCommandService commandService;
    private final AccountQueryService queryService;

    public AccountController(AccountCommandService commandService, AccountQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('CUSTOMER','TELLER','ADMIN')")
    public ResponseEntity<OpenAccountResponse> openAccount(@Valid @RequestBody OpenAccountRequest req,
                                                            Authentication auth) {
        // No existing account to check ownership against here (that's what
        // AccountAccessGuard does for the other endpoints) - so a CUSTOMER
        // opening an account is restricted inline to opening it for
        // themselves. TELLER/ADMIN may open on behalf of any customerId.
        if (auth.getPrincipal() instanceof JwtService.AuthenticatedPrincipal principal
                && principal.role() == com.example.ledger.infrastructure.security.Role.CUSTOMER
                && !req.customerId().equals(principal.customerId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UUID accountId = commandService.openAccount(
                new OpenAccountCommand(req.customerId(), req.currency()),
                metadataFrom(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(new OpenAccountResponse(accountId));
    }

    @PostMapping("/{id}/deposits")
    @PreAuthorize("hasAnyRole('CUSTOMER','TELLER','ADMIN') and @accountAccessGuard.canAccess(#accountId, authentication)")
    public ResponseEntity<Void> deposit(@PathVariable("id") UUID accountId,
                                         @Valid @RequestBody DepositRequest req,
                                         Authentication auth) {
        commandService.deposit(
                new DepositFundsCommand(accountId, req.amount(), req.referenceId(), req.expectedSequenceNumber()),
                metadataFrom(auth));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/withdrawals")
    @PreAuthorize("hasAnyRole('CUSTOMER','TELLER','ADMIN') and @accountAccessGuard.canAccess(#accountId, authentication)")
    public ResponseEntity<Void> withdraw(@PathVariable("id") UUID accountId,
                                          @Valid @RequestBody WithdrawRequest req,
                                          Authentication auth) {
        commandService.withdraw(
                new WithdrawFundsCommand(accountId, req.amount(), req.referenceId(), req.expectedSequenceNumber()),
                metadataFrom(auth));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> freeze(@PathVariable("id") UUID accountId,
                                        @Valid @RequestBody FreezeRequest req,
                                        Authentication auth) {
        commandService.freeze(
                new FreezeAccountCommand(accountId, req.reason(), req.expectedSequenceNumber()),
                metadataFrom(auth));
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER','TELLER','AUDITOR','ADMIN') and @accountAccessGuard.canAccess(#accountId, authentication)")
    public AccountView getAccount(@PathVariable("id") UUID accountId) {
        return AccountView.from(queryService.getCurrentState(accountId));
    }

    /** Fast path: reads the async-updated projection instead of replaying the event store. */
    @GetMapping("/{id}/balance")
    @PreAuthorize("hasAnyRole('CUSTOMER','TELLER','AUDITOR','ADMIN') and @accountAccessGuard.canAccess(#accountId, authentication)")
    public java.math.BigDecimal getBalance(@PathVariable("id") UUID accountId) {
        return queryService.getFastBalance(accountId);
    }

    /**
     * AUDITOR only, deliberately with no ownership bypass via AccountAccessGuard -
     * historical/time-travel access is itself the privileged operation, not
     * something a CUSTOMER gets even for their own account in this model.
     */
    @GetMapping("/{id}/state")
    @PreAuthorize("hasRole('AUDITOR')")
    public AccountView getStateAsOf(@PathVariable("id") UUID accountId,
                                     @RequestParam("asOf") Instant asOf) {
        return AccountView.from(queryService.getStateAsOf(accountId, asOf));
    }

    private EventMetadata metadataFrom(Authentication auth) {
        if (auth.getPrincipal() instanceof JwtService.AuthenticatedPrincipal principal) {
            return new EventMetadata(principal.username(), UUID.randomUUID(), null);
        }
        return EventMetadata.system();
    }
}
