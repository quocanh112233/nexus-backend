CREATE TABLE backfill_state (
    name         text        PRIMARY KEY,
    completed_at timestamptz NOT NULL
);
