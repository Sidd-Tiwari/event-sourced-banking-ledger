-- account_balance_view is NOT authoritative. It exists purely so "what is
-- this account's current balance" is an O(1) indexed lookup instead of
-- replaying the full event stream. If this table were dropped entirely, it
-- could be fully rebuilt by replaying every event in event_store.
--
-- last_event_sequence is the idempotency guard: the projection consumer
-- checks this before applying an incoming event, so re-delivery (at-least-once
-- Kafka semantics + outbox retries) can never double-apply a deposit/withdrawal.

CREATE TABLE account_balance_view (
    account_id           UUID PRIMARY KEY,
    customer_id          UUID NOT NULL,
    currency             VARCHAR(10) NOT NULL,
    current_balance      NUMERIC(19,4) NOT NULL DEFAULT 0,
    status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_event_sequence  BIGINT NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
