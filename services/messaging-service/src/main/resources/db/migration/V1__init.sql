CREATE TABLE channel_refs (
    channel_id uuid        PRIMARY KEY,
    kind       text        NOT NULL CHECK (kind IN ('SERVER', 'DM')),
    server_id  uuid,
    deleted_at timestamptz,
    updated_at timestamptz NOT NULL
);

CREATE INDEX ix_channel_refs_server_id ON channel_refs (server_id);

CREATE TABLE messages (
    id          uuid        PRIMARY KEY,
    channel_id  uuid        NOT NULL,
    author_id   uuid        NOT NULL,
    content     text        NOT NULL,
    reply_to_id uuid,
    nonce       varchar(64),
    created_at  timestamptz NOT NULL,
    edited_at   timestamptz,
    deleted_at  timestamptz,
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_messages_author_nonce UNIQUE (author_id, nonce)
);

CREATE INDEX messages_channel_id_desc ON messages (channel_id, id DESC);

CREATE TABLE message_attachments (
    id                uuid    PRIMARY KEY,
    message_id        uuid    NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    public_id         text    NOT NULL,
    url               text    NOT NULL,
    content_type      text    NOT NULL,
    bytes             bigint  NOT NULL,
    width             int,
    height            int,
    original_filename text    NOT NULL
);

CREATE INDEX ix_message_attachments_message_id ON message_attachments (message_id);

CREATE TABLE message_mentions (
    message_id uuid NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL,
    PRIMARY KEY (message_id, user_id)
);

CREATE TABLE reactions (
    message_id uuid        NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    emoji      varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (message_id, user_id, emoji)
);

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
