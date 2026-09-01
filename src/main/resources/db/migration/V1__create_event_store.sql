-- The event store is the single source of truth for this system.
-- Everything else (projections, snapshots) is a rebuildable derivative.

CREATE TABLE event_store (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE,
    aggregate_id    UUID NOT NULL,
    aggregate_type  VARCHAR(50) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    event_version   INT NOT NULL DEFAULT 1,
    payload         JSONB NOT NULL,
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- This is what enforces optimistic concurrency: two concurrent writers
    -- both trying to write sequence_number = N for the same aggregate will
    -- have exactly one INSERT succeed; the other gets a unique violation,
    -- which EventStore.append() translates into ConcurrentModificationException.
    CONSTRAINT uq_aggregate_sequence UNIQUE (aggregate_id, sequence_number)
);

-- Primary access pattern: "give me all events for this account in order".
CREATE INDEX idx_event_store_aggregate_id ON event_store (aggregate_id);

-- Supports time-travel queries filtering by wall-clock time, and general
-- chronological scans (e.g. a future "all deposits today" report).
CREATE INDEX idx_event_store_created_at ON event_store (created_at);

-- Supports queries like "find all AccountFrozen events" for
-- monitoring/reporting without a full table scan.
CREATE INDEX idx_event_store_event_type ON event_store (event_type);

COMMENT ON TABLE event_store IS
    'Append-only. Rows are never UPDATEd or DELETEd as part of normal business logic.';
