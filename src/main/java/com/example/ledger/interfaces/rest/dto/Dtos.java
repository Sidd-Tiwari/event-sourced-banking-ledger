package com.example.ledger.interfaces.rest.dto;

import com.example.ledger.domain.account.AccountState;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class Dtos {

    private Dtos() {}

    public record OpenAccountRequest(
            @NotNull UUID customerId,
            @NotBlank String currency
    ) {}

    public record OpenAccountResponse(UUID accountId) {}

    public record DepositRequest(
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @NotBlank String referenceId,
            long expectedSequenceNumber
    ) {}

    public record WithdrawRequest(
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @NotBlank String referenceId,
            long expectedSequenceNumber
    ) {}

    public record FreezeRequest(
            @NotBlank String reason,
            long expectedSequenceNumber
    ) {}

    public record AccountView(
            UUID accountId,
            UUID customerId,
            String currency,
            BigDecimal balance,
            String status,
            long sequenceNumber,
            Instant updatedAt
    ) {
        public static AccountView from(AccountState state) {
            return new AccountView(
                    state.accountId(), state.customerId(), state.currency(),
                    state.balance(), state.status().name(),
                    state.sequenceNumber(), state.updatedAt());
        }
    }

    public record ApiError(
            Instant timestamp,
            int status,
            String code,
            String message
    ) {}
}
