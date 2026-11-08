# Mô hình dữ liệu

File này mô tả schema của từng service: bảng, cột chính, ràng buộc, index quan trọng và lý do. ERD: [erd.md](../03-diagrams/erd.md). Quy tắc chung theo [ADR-003](../00-overview/adr/ADR-003-schema-per-service.md).

## 0. Quy ước chung

| Quy ước | Chi tiết |
|--------|---------|
| Tên | Bảng số nhiều `snake_case`; cột `snake_case` |
| Khóa chính | `id uuid` (UUIDv7 sinh ở ứng dụng) trừ bảng nối dùng khóa ghép |
| Thời gian | `timestamptz`, cột `created_at`, `updated_at` |
| Optimistic lock | Cột `version bigint` trên aggregate root hay bị sửa đồng thời |
| Id service khác | Cột `*_id uuid` **không có foreign key** (khác schema) — ghi chú "ref" |
| Migration | `src/main/resources/db/migration/V<n>__<mo_ta>.sql`, Flyway, không sửa migration đã chạy |
| Bảng hạ tầng | Mọi schema có `outbox_event`; schema có consumer có thêm `processed_event` |

Bảng hạ tầng dùng chung cấu trúc:

| Bảng | Cột | Index |
|------|-----|-------|
| `outbox_event` | `id uuid PK, aggregate_type text, aggregate_id uuid, event_type text, event_version int, routing_key text, payload jsonb, headers jsonb, created_at, published_at null, attempts int` | partial index `(id) WHERE published_at IS NULL` |
| `processed_event` | `event_id uuid, consumer text, processed_at` — PK `(event_id, consumer)` | PK |

## 1. Schema `identity` (user `identity_svc`)

| Bảng | Cột chính | Ràng buộc / index |
|------|----------|-------------------|
| `users` | `id, email, username, password_hash, display_name, about_me, avatar_public_id, avatar_url, created_at, updated_at, version` | unique `lower(email)`, unique `lower(username)` |
| `refresh_tokens` | `id, user_id (FK users), token_hash, family_id, expires_at, revoked_at, replaced_by_id, created_at, user_agent, ip` | unique `token_hash`; index `(user_id)`, `(family_id)` |
| `outbox_event` | như trên | |

## 2. Schema `community` (user `community_svc`)

| Bảng | Cột chính | Ràng buộc / index |
|------|----------|-------------------|
| `servers` | `id, name, owner_id (ref user), created_at, updated_at, version` | |
| `roles` | `id, server_id (FK), name, color, permissions bigint, position int, is_default bool, created_at` | unique `(server_id, position)`; partial unique `(server_id) WHERE is_default` |
| `categories` | `id, server_id (FK), name, position, created_at` | index `(server_id, position)` |
| `channels` | `id, server_id (FK), category_id (FK null, ON DELETE SET NULL), name, topic, type ('TEXT'), position, created_at, updated_at, version` | index `(server_id, position)` |
| `channel_permission_overwrites` | `channel_id (FK), target_type ('ROLE','MEMBER'), target_id uuid, allow bigint, deny bigint` | PK `(channel_id, target_type, target_id)` |
| `members` | `server_id (FK), user_id (ref user), joined_at` | PK `(server_id, user_id)`; index `(user_id)` cho "server của tôi" |
| `member_roles` | `server_id, user_id, role_id (FK roles ON DELETE CASCADE)` | PK `(server_id, user_id, role_id)`; FK `(server_id, user_id)` → `members` ON DELETE CASCADE |
| `server_bans` | `server_id (FK), user_id (ref user), banned_by (ref user), reason varchar(512) null, created_at` | PK `(server_id, user_id)` — vừa chặn join vừa là audit tối thiểu (P-34) |
| `invites` | `code varchar(16) PK, server_id (FK), inviter_id (ref user), max_uses int, uses int, expires_at, revoked_at, created_at` | index `(server_id)`; check `uses >= 0` |
| `outbox_event` | | |

Truy vấn nóng `CheckChannelPermissions` = 1 query lấy `members` + `member_roles` + `roles` + overwrites của kênh (đều có index theo `server_id`/`channel_id`).

## 3. Schema `messaging` (user `messaging_svc`)

| Bảng | Cột chính | Ràng buộc / index |
|------|----------|-------------------|
| `channel_refs` | `channel_id PK, kind ('SERVER','DM'), server_id null, deleted_at null, updated_at` | index `(server_id)` (xóa theo server) — **projection** |
| `messages` | `id, channel_id (ref), author_id (ref user), content text, reply_to_id null, nonce varchar(64) null, created_at, edited_at, deleted_at, version` | index **`(channel_id, id DESC)`** cho phân trang keyset; unique `(author_id, nonce)` |
| `message_attachments` | `id, message_id (FK), public_id, url, content_type, bytes, width, height, original_filename` | index `(message_id)` |
| `message_mentions` | `message_id (FK), user_id` | PK `(message_id, user_id)` |
| `reactions` | `message_id (FK), user_id, emoji varchar(32), created_at` | PK `(message_id, user_id, emoji)` |
| `friend_requests` | `id, requester_id, addressee_id, status, created_at, responded_at` | partial unique `(least(requester_id,addressee_id), greatest(...)) WHERE status='PENDING'`; index `(addressee_id, status)`, `(requester_id, status)` |
| `friendships` | `user_low_id, user_high_id, created_at` | PK `(user_low_id, user_high_id)`; check `user_low_id < user_high_id`; index `(user_high_id)` |
| `dm_channels` | `id, user_low_id, user_high_id, created_at` | unique `(user_low_id, user_high_id)` |
| `outbox_event`, `processed_event` | | |

**Phân trang keyset** (FR-055, NFR-003):

```sql
SELECT * FROM messages
WHERE channel_id = :channelId AND id < :before
ORDER BY id DESC
LIMIT :limit;
```

Dùng được index `(channel_id, id DESC)` → độ phức tạp không phụ thuộc trang thứ mấy (khác `OFFSET`). Bài tập P9: đo `EXPLAIN ANALYZE` keyset vs `OFFSET 100000` trên 1 triệu dòng, ghi số vào `docs/perf/`.

## 4. Schema `notification` (user `notification_svc`)

| Bảng | Cột chính | Ràng buộc / index |
|------|----------|-------------------|
| `channel_activity` | `channel_id PK, server_id null, last_message_id null, last_message_at, deleted_at` | index `(server_id)` — **projection** |
| `server_members` | `server_id, user_id, joined_at` | PK `(server_id, user_id)` — **projection** |
| `read_states` | `user_id, channel_id, last_read_message_id null, mention_count int, updated_at` | PK `(user_id, channel_id)` |
| `mentions` | `message_id, user_id, channel_id, server_id null, author_id, created_at` | PK `(message_id, user_id)`; index `(user_id, channel_id)` |
| `outbox_event`, `processed_event` | | |

`GET /api/v1/read-states` = join `read_states` với `channel_activity` theo `user_id` (cùng schema, hợp lệ).

## 5. Presence (Redis)

| Key | Kiểu | Nội dung | TTL |
|-----|------|---------|-----|
| `presence:user:{userId}:sessions` | Set | các `sessionId` | không |
| `presence:session:{sessionId}` | String | `userId|instanceId` | không |
| `presence:instance:{instanceId}:sessions` | Set | các `sessionId` của instance | không |
| `presence:instance:{instanceId}:alive` | String | thời điểm heartbeat cuối | 90 s |
| `presence:status:{userId}` | String | `ONLINE` | không (xóa khi offline) |
| `presence:rel:{userId}:servers` | Set | `serverId` user đang là member — projection từ `community.events` | không |
| `presence:rel:{userId}:friends` | Set | `userId` bạn bè — projection từ `messaging.events` | không |
| `presence:rel:{userId}:loaded` | String | Đánh dấu đã nạp đủ từ gRPC | 24 h (hết hạn → nạp lại, tự sửa sai lệch) |
| `presence:server:{serverId}:members` | Set | Chỉ dùng để xử lý `ServerDeleted` (biết user nào cần bỏ server) | không |

Quy tắc projection: event chỉ được áp vào user **đã có** `loaded` (user chưa nạp sẽ được nạp đầy đủ từ gRPC khi cần, tránh projection thiếu). Kiểm tra `canSee(viewer, target)`: `SISMEMBER presence:rel:{viewer}:friends target` hoặc `SINTERCARD 2 presence:rel:{viewer}:servers presence:rel:{target}:servers LIMIT 1` > 0 — O(min(|A|,|B|)), vài chục phần tử mỗi user.

Thao tác thêm/xóa session + đổi trạng thái chạy trong **một Lua script** để nguyên tử khi nhiều instance Presence xử lý đồng thời.

## 6. Redis dùng chung khác

| Key | Chủ sở hữu | Nội dung | TTL |
|-----|-----------|---------|-----|
| `perm:{channelId}:{userId}` | messaging | Bitmask quyền kênh dạng `serverId\|bits\|ver` (cache-aside, fail-open khi Redis lỗi); `ver` là `perm:ver:{serverId}` lúc ghi, đọc lệch version thì coi là miss; metric `nexus.permission.cache.hits` / `nexus.permission.cache.misses` | 60 s |
| `perm:ver:{serverId}` | messaging | Số phiên bản quyền của server, `INCR` (trong transaction xử lý event và lại sau commit) khi có event đổi quyền, kick, ban, xóa kênh/server | không |
| `rl:{route}:{key}` | api-gateway | Token bucket | theo cấu hình |

Mỗi service dùng **prefix riêng** và (khuyến nghị) Redis ACL user riêng — áp dụng tinh thần "dữ liệu thuộc một service" cho Redis.
