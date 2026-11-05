# ERD theo từng schema

File này vẽ ERD cho từng schema bằng `erDiagram`. Mỗi schema là một sơ đồ riêng vì **không có quan hệ chéo schema**; cột tham chiếu sang service khác ghi chú `ref ...` và không có foreign key. Mô tả cột/index: [data-model.md](../02-architecture/data-model.md). Bảng `outbox_event`, `processed_event` có ở mọi schema nên không vẽ lại.

## 1. Schema `identity`

```mermaid
erDiagram
    users ||--o{ refresh_tokens : "sở hữu"
    users {
        uuid id PK
        varchar email UK
        varchar username UK
        varchar password_hash
        varchar display_name
        varchar about_me
        varchar avatar_public_id
        varchar avatar_url
        timestamptz created_at
        bigint version
    }
    refresh_tokens {
        uuid id PK
        uuid user_id FK
        varchar token_hash UK
        uuid family_id
        timestamptz expires_at
        timestamptz revoked_at
        uuid replaced_by_id
    }
```

## 2. Schema `community`

```mermaid
erDiagram
    servers ||--o{ roles : "có"
    servers ||--o{ categories : "có"
    servers ||--o{ channels : "có"
    servers ||--o{ members : "có"
    servers ||--o{ invites : "có"
    servers ||--o{ server_bans : "cấm"
    categories |o--o{ channels : "nhóm"
    channels ||--o{ channel_permission_overwrites : "ghi đè"
    members ||--o{ member_roles : "được gán"
    roles ||--o{ member_roles : "gán cho"
    servers {
        uuid id PK
        varchar name
        uuid owner_id "ref identity.users"
        bigint version
    }
    roles {
        uuid id PK
        uuid server_id FK
        varchar name
        bigint permissions
        int position
        boolean is_default
    }
    categories {
        uuid id PK
        uuid server_id FK
        varchar name
        int position
    }
    channels {
        uuid id PK
        uuid server_id FK
        uuid category_id FK
        varchar name
        varchar topic
        varchar type
        int position
        bigint version
    }
    channel_permission_overwrites {
        uuid channel_id PK, FK
        varchar target_type PK
        uuid target_id PK "role id hoặc user id"
        bigint allow
        bigint deny
    }
    members {
        uuid server_id PK, FK
        uuid user_id PK "ref identity.users"
        timestamptz joined_at
    }
    member_roles {
        uuid server_id PK, FK
        uuid user_id PK, FK
        uuid role_id PK, FK
    }
    server_bans {
        uuid server_id PK, FK
        uuid user_id PK "ref identity.users"
        uuid banned_by "ref identity.users"
        varchar reason
        timestamptz created_at
    }
    invites {
        varchar code PK
        uuid server_id FK
        uuid inviter_id "ref identity.users"
        int max_uses
        int uses
        timestamptz expires_at
        timestamptz revoked_at
    }
```

## 3. Schema `messaging`

```mermaid
erDiagram
    channel_refs ||--o{ messages : "chứa"
    messages ||--o{ message_attachments : "đính kèm"
    messages ||--o{ message_mentions : "nhắc tới"
    messages ||--o{ reactions : "nhận"
    messages |o--o{ messages : "reply"
    dm_channels ||--|| channel_refs : "là kênh DM"
    channel_refs {
        uuid channel_id PK "ref community.channels hoặc dm_channels"
        varchar kind
        uuid server_id "ref community.servers"
        timestamptz deleted_at
    }
    messages {
        uuid id PK
        uuid channel_id FK
        uuid author_id "ref identity.users"
        text content
        uuid reply_to_id FK
        varchar nonce
        timestamptz edited_at
        timestamptz deleted_at
        bigint version
    }
    message_attachments {
        uuid id PK
        uuid message_id FK
        varchar public_id
        varchar url
        varchar content_type
        bigint bytes
    }
    message_mentions {
        uuid message_id PK, FK
        uuid user_id PK "ref identity.users"
    }
    reactions {
        uuid message_id PK, FK
        uuid user_id PK "ref identity.users"
        varchar emoji PK
        timestamptz created_at
    }
    friend_requests {
        uuid id PK
        uuid requester_id "ref identity.users"
        uuid addressee_id "ref identity.users"
        varchar status
        timestamptz responded_at
    }
    friendships {
        uuid user_low_id PK "ref identity.users"
        uuid user_high_id PK "ref identity.users"
        timestamptz created_at
    }
    dm_channels {
        uuid id PK
        uuid user_low_id UK
        uuid user_high_id UK
        timestamptz created_at
    }
```

Ghi chú: `messages.channel_id` → `channel_refs` là FK **trong cùng schema** (hợp lệ); `channel_refs` lại là bản chiếu của dữ liệu Community. `dm_channels.id = channel_refs.channel_id` khi `kind = DM`.

## 4. Schema `notification`

```mermaid
erDiagram
    channel_activity ||--o{ read_states : "theo dõi"
    channel_activity ||--o{ mentions : "thuộc kênh"
    server_members {
        uuid server_id PK "ref community.servers"
        uuid user_id PK "ref identity.users"
        timestamptz joined_at
    }
    channel_activity {
        uuid channel_id PK "ref kênh server hoặc DM"
        uuid server_id "null nếu DM"
        uuid last_message_id
        timestamptz last_message_at
        timestamptz deleted_at
    }
    read_states {
        uuid user_id PK "ref identity.users"
        uuid channel_id PK, FK
        uuid last_read_message_id
        int mention_count
        timestamptz updated_at
    }
    mentions {
        uuid message_id PK "ref messaging.messages"
        uuid user_id PK "ref identity.users"
        uuid channel_id FK
        uuid author_id
        timestamptz created_at
    }
```

## 5. Presence

Không có schema Postgres — chỉ Redis, xem [data-model.md](../02-architecture/data-model.md#5-presence-redis).
