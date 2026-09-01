package com.example.ledger.domain.account.events;

import java.util.UUID;

public record AccountOpened(
        UUID accountId,
        UUID customerId,
        String currency
) implements DomainEvent {

    @Override
    public String eventType() {
        return "AccountOpened";
    }
}
