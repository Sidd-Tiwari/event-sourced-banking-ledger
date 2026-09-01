package com.example.ledger.interfaces.rest;

import com.example.ledger.application.command.AccountCommandService;
import com.example.ledger.application.query.AccountQueryService;
import com.example.ledger.domain.account.commands.Commands.DepositFundsCommand;
import com.example.ledger.domain.account.commands.Commands.FreezeAccountCommand;
import com.example.ledger.domain.account.commands.Commands.OpenAccountCommand;
import com.example.ledger.domain.account.commands.Commands.WithdrawFundsCommand;
import com.example.ledger.infrastructure.persistence.EventMetadata;
import com.example.ledger.interfaces.rest.dto.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * CQRS split at the API layer: POST endpoints are commands (mutate via the
 * event store), GET endpoints are queries (read derived state). No endpoint
 * here exposes JPA/persistence types - everything crosses the wire as a DTO.
 *
 * No auth yet - added in a later commit once the domain/event-store logic
 * is validated in isolation first.
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
    public ResponseEntity<OpenAccountResponse> openAccount(@Valid @RequestBody OpenAccountRequest req) {
        UUID accountId = commandService.openAccount(
                new OpenAccountCommand(req.customerId(), req.currency()),
                EventMetadata.system());
        return ResponseEntity.status(HttpStatus.CREATED).body(new OpenAccountResponse(accountId));
    }

    @PostMapping("/{id}/deposits")
    public ResponseEntity<Void> deposit(@PathVariable("id") UUID accountId,
                                         @Valid @RequestBody DepositRequest req) {
        commandService.deposit(
                new DepositFundsCommand(accountId, req.amount(), req.referenceId(), req.expectedSequenceNumber()),
                EventMetadata.system());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/withdrawals")
    public ResponseEntity<Void> withdraw(@PathVariable("id") UUID accountId,
                                          @Valid @RequestBody WithdrawRequest req) {
        commandService.withdraw(
                new WithdrawFundsCommand(accountId, req.amount(), req.referenceId(), req.expectedSequenceNumber()),
                EventMetadata.system());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/freeze")
    public ResponseEntity<Void> freeze(@PathVariable("id") UUID accountId,
                                        @Valid @RequestBody FreezeRequest req) {
        commandService.freeze(
                new FreezeAccountCommand(accountId, req.reason(), req.expectedSequenceNumber()),
                EventMetadata.system());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}")
    public AccountView getAccount(@PathVariable("id") UUID accountId) {
        return AccountView.from(queryService.getCurrentState(accountId));
    }

    @GetMapping("/{id}/state")
    public AccountView getStateAsOf(@PathVariable("id") UUID accountId,
                                     @RequestParam("asOf") Instant asOf) {
        return AccountView.from(queryService.getStateAsOf(accountId, asOf));
    }
}
