CREATE TABLE channel_activity (
    channel_id      uuid        PRIMARY KEY,
    server_id       uuid,
    last_message_id uuid,
    last_message_at timestamptz,
    deleted_at      timestamptz
);

CREATE INDEX channel_activity_server_idx ON channel_activity (server_id);

CREATE TABLE server_members (
    server_id uuid        NOT NULL,
    user_id   uuid        NOT NULL,
    joined_at timestamptz NOT NULL,
    PRIMARY KEY (server_id, user_id)
);

CREATE INDEX server_members_user_idx ON server_members (user_id);

CREATE TABLE dm_participants (
    channel_id uuid NOT NULL,
    user_id    uuid NOT NULL,
    PRIMARY KEY (channel_id, user_id)
);

CREATE INDEX dm_participants_user_idx ON dm_participants (user_id);

CREATE TABLE read_states (
    user_id              uuid        NOT NULL,
    channel_id           uuid        NOT NULL,
    last_read_message_id uuid,
    mention_count        int         NOT NULL DEFAULT 0 CHECK (mention_count >= 0),
    updated_at           timestamptz NOT NULL,
    PRIMARY KEY (user_id, channel_id)
);

CREATE TABLE mentions (
    message_id uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    channel_id uuid        NOT NULL,
    server_id  uuid,
    author_id  uuid        NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX mentions_user_channel_idx ON mentions (user_id, channel_id);

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

CREATE TABLE processed_event (
    event_id     uuid        NOT NULL,
    consumer     text        NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (event_id, consumer)
);
