package com.example.ledger.domain.account.events;

/**
 * Marker interface for all Account domain events.
 *
 * Design note: events are pure data (records) - no behaviour, no setters.
 * They describe a fact that already happened ("FundsDeposited"), never an
 * intent ("DepositFunds" - that's a Command, see domain.account.commands).
 *
 * eventType() is used as the discriminator stored in event_store.event_type
 * and is what the aggregate's apply() switches on during replay.
 */
public sealed interface DomainEvent
        permits AccountOpened, FundsDeposited, FundsWithdrawn, AccountFrozen {

    String eventType();
}
