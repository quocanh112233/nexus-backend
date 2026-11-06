CREATE TABLE server_bans (
    server_id  uuid         NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    user_id    uuid         NOT NULL,
    banned_by  uuid         NOT NULL,
    reason     varchar(512),
    created_at timestamptz  NOT NULL,
    PRIMARY KEY (server_id, user_id)
);
