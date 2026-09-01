package com.example.ledger.domain.account.events;

import java.math.BigDecimal;
import java.util.UUID;

public record FundsWithdrawn(
        UUID accountId,
        BigDecimal amount,
        String referenceId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "FundsWithdrawn";
    }
}
