-- Snapshots are a pure performance optimization. If this table were dropped
-- entirely, every query would still produce the correct result by replaying
-- from event_store - it would just be slower. Time-travel correctness must
-- NEVER depend on a snapshot existing.

CREATE TABLE account_snapshots (
    account_id          UUID NOT NULL,
    sequence_number     BIGINT NOT NULL,
    state               JSONB NOT NULL,
    -- The created_at of the EVENT that produced this snapshot (not "now").
    -- This is what lets a time-travel query pick a snapshot purely by
    -- comparing timestamps, without a second join back to event_store.
    event_created_at    TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, sequence_number)
);

-- The lookup this table exists to serve: "give me the latest snapshot for
-- this account at or before sequence/time X".
CREATE INDEX idx_account_snapshots_account_seq ON account_snapshots (account_id, sequence_number DESC);
