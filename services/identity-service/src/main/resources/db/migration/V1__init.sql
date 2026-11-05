CREATE TABLE users (
    id               uuid         PRIMARY KEY,
    email            text         NOT NULL,
    username         text         NOT NULL,
    password_hash    text         NOT NULL,
    display_name     text         NOT NULL,
    about_me         text,
    avatar_public_id text,
    avatar_url       text,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    version          bigint       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_users_email ON users (lower(email));
CREATE UNIQUE INDEX ux_users_username ON users (lower(username));

CREATE TABLE refresh_tokens (
    id             uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES users (id),
    token_hash     text        NOT NULL,
    family_id      uuid        NOT NULL,
    expires_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    replaced_by_id uuid,
    created_at     timestamptz NOT NULL,
    user_agent     text,
    ip             text
);

CREATE UNIQUE INDEX ux_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family_id ON refresh_tokens (family_id);

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
