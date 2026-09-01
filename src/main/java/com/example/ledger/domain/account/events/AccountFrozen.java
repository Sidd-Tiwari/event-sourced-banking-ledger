package com.example.ledger.domain.account.events;

import java.util.UUID;

public record AccountFrozen(
        UUID accountId,
        String reason
) implements DomainEvent {

    @Override
    public String eventType() {
        return "AccountFrozen";
    }
}
