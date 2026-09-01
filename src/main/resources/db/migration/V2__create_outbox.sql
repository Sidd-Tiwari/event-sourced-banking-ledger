-- Outbox pattern: solves the dual-write problem (DB commit succeeds but Kafka
-- publish fails, or vice versa) by writing the "intent to publish" in the SAME
-- transaction as the event store append. A separate poller (OutboxPublisher)
-- then reads unpublished rows and pushes them to Kafka, marking them published
-- only after a successful send.
--
-- This gives at-least-once delivery to Kafka, NOT exactly-once: if the app
-- crashes between a successful Kafka send and marking the row published, the
-- poller will resend it on restart. Consumers MUST be idempotent (see
-- account_balance_view.last_event_sequence) to tolerate that.

CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE,
    aggregate_id    UUID NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL,
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    publish_attempts INT NOT NULL DEFAULT 0
);

-- The poller's main query: "give me unpublished rows, oldest first".
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
