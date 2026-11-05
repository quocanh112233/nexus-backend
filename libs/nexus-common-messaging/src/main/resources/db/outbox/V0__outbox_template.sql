CREATE TABLE outbox_event (
    id             uuid        PRIMARY KEY,
    aggregate_type text        NOT NULL,
    aggregate_id   uuid        NOT NULL,
    event_type     text        NOT NULL,
    event_version  int         NOT NULL,
    routing_key    text        NOT NULL,
    payload        jsonb       NOT NULL,
    headers        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL,
    published_at   timestamptz,
    attempts       int         NOT NULL DEFAULT 0
);

CREATE INDEX outbox_event_unpublished_idx ON outbox_event (id) WHERE published_at IS NULL;
