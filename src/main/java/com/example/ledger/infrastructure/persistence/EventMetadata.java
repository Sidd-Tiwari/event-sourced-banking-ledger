package com.example.ledger.infrastructure.persistence;

import java.util.UUID;

/**
 * Attached to every stored event for auditability. userId is who initiated
 * the command; correlationId ties a whole request/workflow together;
 * causationId points at the specific command/event that directly caused
 * this one (useful once TransferFunds spans two aggregates in a later phase).
 */
public record EventMetadata(
        String userId,
        UUID correlationId,
        UUID causationId
) {
    public static EventMetadata system() {
        return new EventMetadata("system", UUID.randomUUID(), null);
    }
}
