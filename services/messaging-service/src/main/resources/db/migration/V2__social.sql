CREATE TABLE friend_requests (
    id           uuid        PRIMARY KEY,
    requester_id uuid        NOT NULL,
    addressee_id uuid        NOT NULL,
    status       text        NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELED')),
    version      bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    responded_at timestamptz,
    CHECK (requester_id <> addressee_id)
);

CREATE UNIQUE INDEX ux_friend_requests_pending
    ON friend_requests ((LEAST(requester_id, addressee_id)), (GREATEST(requester_id, addressee_id)))
    WHERE status = 'PENDING';

CREATE INDEX ix_friend_requests_addressee_status ON friend_requests (addressee_id, status);

CREATE INDEX ix_friend_requests_requester_status ON friend_requests (requester_id, status);

CREATE TABLE friendships (
    user_low_id  uuid        NOT NULL,
    user_high_id uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    PRIMARY KEY (user_low_id, user_high_id),
    CHECK (user_low_id < user_high_id)
);

CREATE INDEX ix_friendships_user_high_id ON friendships (user_high_id);

CREATE TABLE dm_channels (
    id           uuid        PRIMARY KEY,
    user_low_id  uuid        NOT NULL,
    user_high_id uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    CONSTRAINT uq_dm_channels_pair UNIQUE (user_low_id, user_high_id),
    CHECK (user_low_id < user_high_id)
);

CREATE INDEX ix_dm_channels_user_high_id ON dm_channels (user_high_id);
