CREATE TABLE servers (
    id         uuid         PRIMARY KEY,
    name       text         NOT NULL,
    owner_id   uuid         NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0
);

CREATE TABLE roles (
    id          uuid        PRIMARY KEY,
    server_id   uuid        NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    name        text        NOT NULL,
    color       int         NOT NULL DEFAULT 0,
    permissions bigint      NOT NULL,
    position    int         NOT NULL,
    is_default  boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL,
    CONSTRAINT ux_roles_server_position UNIQUE (server_id, position)
);

CREATE UNIQUE INDEX ux_roles_server_default ON roles (server_id) WHERE is_default;

CREATE TABLE categories (
    id         uuid        PRIMARY KEY,
    server_id  uuid        NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    name       text        NOT NULL,
    position   int         NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX ix_categories_server_position ON categories (server_id, position);

CREATE TABLE channels (
    id          uuid        PRIMARY KEY,
    server_id   uuid        NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    category_id uuid        REFERENCES categories (id) ON DELETE SET NULL,
    name        text        NOT NULL,
    topic       text,
    type        text        NOT NULL DEFAULT 'TEXT',
    position    int         NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_channels_type CHECK (type IN ('TEXT'))
);

CREATE INDEX ix_channels_server_position ON channels (server_id, position);

CREATE TABLE channel_permission_overwrites (
    channel_id  uuid   NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    target_type text   NOT NULL,
    target_id   uuid   NOT NULL,
    allow       bigint NOT NULL DEFAULT 0,
    deny        bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (channel_id, target_type, target_id),
    CONSTRAINT ck_overwrites_target_type CHECK (target_type IN ('ROLE', 'MEMBER'))
);

CREATE TABLE members (
    server_id uuid        NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    user_id   uuid        NOT NULL,
    joined_at timestamptz NOT NULL,
    PRIMARY KEY (server_id, user_id)
);

CREATE INDEX ix_members_user_id ON members (user_id);

CREATE TABLE member_roles (
    server_id uuid NOT NULL,
    user_id   uuid NOT NULL,
    role_id   uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (server_id, user_id, role_id),
    FOREIGN KEY (server_id, user_id) REFERENCES members (server_id, user_id) ON DELETE CASCADE
);

CREATE TABLE invites (
    code       varchar(16) PRIMARY KEY,
    server_id  uuid        NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    inviter_id uuid        NOT NULL,
    max_uses   int         NOT NULL DEFAULT 0,
    uses       int         NOT NULL DEFAULT 0,
    expires_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_invites_uses CHECK (
        max_uses >= 0 AND uses >= 0 AND (max_uses = 0 OR uses <= max_uses))
);

CREATE INDEX ix_invites_server_id ON invites (server_id);

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
