# Đặc tả từng service

File này mô tả cho từng service: trách nhiệm, mô hình DDD (aggregate, entity, value object, domain service), REST API ở biên, gRPC (server/client), event phát và nghe. Tên ở đây là tên chuẩn dùng trong code. Event chi tiết: [event-catalog.md](event-catalog.md); proto: [grpc-design.md](grpc-design.md); bảng: [data-model.md](data-model.md).

Mọi service dùng cùng cách lưu trữ: `XxxRepository` (interface trong domain) → `XxxRepositoryImpl` → `XxxDao` + `XxxMapper` + `XxxJpaEntity` ở `infrastructure/persistence` ([ADR-009](../00-overview/adr/ADR-009-dao-layer.md), [backend-structure.md](../04-project-structure/backend-structure.md#31-lớp-persistence-repository--repositoryimpl--dao-adr-009-người-dùng-đã-chọn)). Presence dùng Redis nên thay DAO bằng `RedisPresenceStore` sau interface `PresenceRepository`.

Tóm tắt giao tiếp:

| Service | REST (biên, qua gateway) | gRPC server | gRPC client gọi tới | Exchange phát | Nghe từ exchange |
|---------|-------------------------|-------------|---------------------|---------------|------------------|
| identity-service | `/api/v1/auth/**`, `/api/v1/users/**` | `identity.v1.UserService` | - | `identity.events` | - |
| community-service | `/api/v1/servers/**`, `/api/v1/invites/**` | `community.v1.PermissionService` | - | `community.events` | - |
| messaging-service | `/api/v1/channels/**`, `/api/v1/attachments/**`, `/api/v1/friends/**`, `/api/v1/dms/**` | `messaging.v1.ChannelAccessService`, `messaging.v1.SocialService` | Community `PermissionService`, Identity `UserService` | `messaging.events` | `community.events` |
| presence-service | `/api/v1/presence` | - | Community `PermissionService`, Messaging `SocialService` (nạp bù projection) | `presence.events` | `realtime.events`, `community.events`, `messaging.events` |
| notification-service | `/api/v1/read-states/**` | - | - | `notification.events` | `community.events`, `messaging.events` |
| realtime-gateway | `/ws` (WebSocket) | - | Messaging `ChannelAccessService`, Community `PermissionService` | `realtime.events` | tất cả exchange (queue riêng mỗi instance) |
| api-gateway | định tuyến mọi `/api/**`, `/ws` | - | - | - | - |

---

## 1. identity-service

**Trách nhiệm:** đăng ký, đăng nhập, refresh token, profile, chữ ký upload avatar, phát JWKS.

| Loại | Tên | Mô tả / bất biến |
|------|-----|-----------------|
| Aggregate root | `User` | email, username duy nhất (lowercase); đổi profile phát `UserProfileUpdated` |
| Aggregate root | `RefreshToken` | Thuộc một `familyId`; chỉ dùng một lần; reuse → thu hồi cả family |
| Value object | `Email`, `Username`, `DisplayName`, `PasswordHash`, `MediaRef(publicId, url)`, `TokenHash` | Tự validate trong constructor |
| Domain service | `RefreshTokenRotationService` | Rotation + reuse detection |
| Port (application) | `PasswordHasher`, `TokenIssuer`, `UploadSigner` | Hiện thực ở `infrastructure` |
| Domain event | `UserRegistered`, `UserProfileUpdated` | |

| REST | Mô tả | FR |
|------|------|----|
| `POST /api/v1/auth/register` | Đăng ký | FR-001 |
| `POST /api/v1/auth/login` | Đăng nhập | FR-002 |
| `POST /api/v1/auth/refresh` | Làm mới token (cookie) | FR-003 |
| `POST /api/v1/auth/logout` | Đăng xuất | FR-004 |
| `GET /api/v1/users/me`, `PATCH /api/v1/users/me` | Profile của tôi | FR-006, FR-007 |
| `POST /api/v1/users/me/avatar/signature` | Cấp chữ ký Cloudinary | FR-100 |
| `PUT /api/v1/users/me/avatar` | Lưu avatar `{publicId}` | FR-008, FR-102 |
| `GET /api/v1/users/{userId}` | Public profile | FR-006 |
| `GET /api/v1/users?ids=a,b` | Nhiều public profile (≤100) cho frontend hiển thị tên/avatar | FR-006 |
| `GET /api/v1/users/by-username/{username}` | Tra cứu | FR-010 |
| `GET /.well-known/jwks.json` | Public key (chỉ trong mạng nội bộ) | - |

gRPC server: `identity.v1.UserService` — `GetUser`, `BatchGetUsers`.

## 2. community-service

**Trách nhiệm:** server, category, kênh, member, role, permission, invite. Nguồn sự thật về quyền.

| Loại | Tên | Mô tả / bất biến |
|------|-----|-----------------|
| Aggregate root | `Server` (chứa danh sách `Role`) | Đúng một role `@everyone`; `position` role duy nhất; owner không đổi trong MVP |
| Entity | `Role` | `permissions` (bitset), `position`, `color` |
| Aggregate root | `Category` | `position` trong server |
| Aggregate root | `Channel` (chứa `PermissionOverwrite`) | Tên hợp lệ; tối đa 1 overwrite cho mỗi `(targetType, targetId)` |
| Aggregate root | `Member` (chứa `roleIds`) | Khóa `(serverId, userId)`; không chứa `@everyone` trong `roleIds` |
| Aggregate root | `Invite` | `uses ≤ maxUses`; hết hạn/thu hồi thì không dùng được |
| Aggregate root | `ServerBan` | Khóa `(serverId, userId)`; lưu `bannedBy`, `reason`, `createdAt` (audit tối thiểu — P-34); tồn tại ban → không join được |
| Value object | `ServerName`, `ChannelName`, `PermissionSet` (bitset, phép `allow/deny`), `InviteCode`, `Position` | |
| Domain service | `PermissionCalculator`, `RoleHierarchyPolicy` (dùng cho cả gán role và kick/ban) | Thuật toán ở [permission-model.md](../01-requirements/permission-model.md) |
| Domain event | `ServerCreated`, `ServerUpdated`, `ServerDeleted`, `CategoryCreated`, `CategoryUpdated`, `CategoryDeleted`, `ChannelCreated`, `ChannelUpdated`, `ChannelDeleted`, `ChannelPermissionsChanged`, `MemberJoined`, `MemberLeft`, `MemberKicked`, `MemberBanned`, `MemberUnbanned`, `MemberRolesChanged`, `RoleCreated`, `RoleUpdated`, `RoleDeleted` | |

Lý do chia nhiều aggregate nhỏ: tránh một aggregate `Server` khổng lồ bị khóa mỗi khi có member join (tranh chấp ghi). Role nằm trong `Server` vì bất biến "position duy nhất" cần nhìn mọi role.

| REST | Mô tả | FR |
|------|------|----|
| `POST /api/v1/servers`, `GET /api/v1/servers` | Tạo, danh sách server của tôi | FR-020, FR-023 |
| `GET/PATCH/DELETE /api/v1/servers/{serverId}` | Chi tiết (kèm category, kênh thấy được, role), đổi tên, xóa | FR-021, FR-022 |
| `GET /api/v1/servers/{serverId}/permissions/@me` | Quyền hiệu lực của tôi theo từng kênh (cho UI ẩn/hiện nút) | FR-034 |
| `POST /api/v1/servers/{serverId}/categories`, `PATCH/DELETE .../categories/{categoryId}` | Category | FR-024 |
| `POST /api/v1/servers/{serverId}/channels`, `PATCH/DELETE .../channels/{channelId}` | Kênh | FR-025 |
| `PUT/DELETE /api/v1/servers/{serverId}/channels/{channelId}/permissions/{targetType}/{targetId}` | Overwrite | FR-034 |
| `GET/POST /api/v1/servers/{serverId}/roles`, `PATCH/DELETE .../roles/{roleId}` | Role | FR-032 |
| `GET /api/v1/servers/{serverId}/members` | Thành viên (cursor) | FR-031 |
| `PUT /api/v1/servers/{serverId}/members/{userId}/roles` | Gán role | FR-033 |
| `DELETE /api/v1/servers/{serverId}/members/@me` | Rời server | FR-030 |
| `DELETE /api/v1/servers/{serverId}/members/{userId}` | Kick | FR-036 |
| `GET /api/v1/servers/{serverId}/bans` | Danh sách ban (cursor) | FR-039 |
| `PUT /api/v1/servers/{serverId}/bans/{userId}` | Ban `{reason?}` (idempotent) | FR-037 |
| `DELETE /api/v1/servers/{serverId}/bans/{userId}` | Gỡ ban | FR-039 |
| `POST/GET /api/v1/servers/{serverId}/invites` | Tạo, liệt kê invite | FR-026 |
| `GET /api/v1/invites/{code}`, `POST /api/v1/invites/{code}/accept`, `DELETE /api/v1/invites/{code}` | Preview, tham gia, thu hồi | FR-027..FR-029 |

**Quyết định P2** (dòng đầu về "chỉ owner" đã được P3 thay bằng permission thật, xem bên dưới):

- Ban đầu chỉ owner đổi tên/xóa server, quản lý category/kênh và liệt kê invite, mọi member tạo được invite; owner không rời được server (403).
- Invite: mã 8 ký tự `[A-Za-z0-9]`, tạo bằng `maxUses` (0 = không giới hạn) và `expiresInSeconds` (60..2592000, mặc định 7 ngày). Preview trả 404 khi mã không tồn tại, accept trả 410 khi hết lượt, hết hạn hoặc đã thu hồi.
- Accept chạy `addIfAbsent` rồi `tryConsume` trong một transaction (`INSERT ... ON CONFLICT DO NOTHING` + `UPDATE ... WHERE uses < max_uses`), nên join đồng thời không vượt `maxUses`.
- `position` của category/kênh không dồn lại sau khi xóa, sắp theo `(position, id)`.
- Con trỏ danh sách member là base64url (không padding) của `userId` cuối trang.
- Event `Member*` dùng `aggregateId = serverId`; xóa category chỉ phát `CategoryDeleted`.

**Quyết định P3:**

- Role mới mặc định ở `position` 1 và `position` luôn được đánh số dày (1..n, không khoảng trống) khi tạo, đổi chỗ, xóa; unique `(server_id, position)` là `DEFERRABLE INITIALLY DEFERRED` để đổi chỗ trong một transaction.
- `RoleHierarchyPolicy.canPlaceAt` có hai chế độ: chèn role mới được đặt ở `position` ≤ role cao nhất của actor, di chuyển role có sẵn chỉ được đến `position` < role cao nhất của actor.
- Overwrite của kênh chỉ nhận và chỉ áp các bit trong `PermissionSet.CHANNEL_SCOPE` (bit 2 đến 10), nên bit phạm vi server (`ADMINISTRATOR`, `KICK_MEMBERS`, `BAN_MEMBERS`...) không bị overwrite đổi; `allow` phải là quyền actor có thể cấp (`canGrant`). Đích MEMBER của overwrite cũng theo luật thứ bậc: actor chỉ đặt được cho member thấp hơn mình.
- `GET /servers/{id}` ẩn kênh người gọi không có `VIEW_CHANNEL` nhưng chưa ẩn category rỗng; `permissions/@me` chỉ liệt kê kênh nhìn thấy được.
- Event `RoleCreated` và `RoleUpdated` mang `permissions` dạng chuỗi số thập phân, `ChannelPermissionsChanged` phát ở mỗi PUT/DELETE overwrite kể cả khi giá trị không đổi.
- Gán role (`PUT .../members/{userId}/roles`) đọc hàng member bằng `SELECT ... FOR UPDATE` rồi mới tính hiệu đối xứng tập role cũ và mới, để hai lần gán đồng thời không ghi đè lẫn nhau (lost update trên `member_roles`).
- Ban, gỡ ban, kick và accept invite cùng lấy `pg_advisory_xact_lock` theo cặp `(server, user)` trước khi đọc/ghi `members` và `server_bans`, nên ban và join đồng thời không bao giờ để lại member bị ban (`BanJoinConcurrencyIT`). Ban xong thì xóa member và overwrite MEMBER của user. Ban người chưa là member được phép; ban lại im lặng trả `204`; `reason` rỗng lưu `null`.

gRPC server: `community.v1.PermissionService` — `CheckChannelPermissions`, `CheckServerMembership`, `ListServerIdsForUser`.

## 3. messaging-service

**Trách nhiệm:** tin nhắn (kênh server + DM), reply, reaction, đính kèm, mention (lưu), bạn bè, DM.

| Loại | Tên | Mô tả / bất biến |
|------|-----|-----------------|
| Aggregate root | `Message` (chứa `Attachment`, `Mention`) | 1-2000 ký tự (hoặc có attachment); ≤5 attachment; ≤20 mention; chỉ tác giả sửa; xóa mềm; `version` tăng mỗi lần sửa |
| Aggregate root | `Reaction` | Khóa `(messageId, userId, emoji)`; tách khỏi `Message` để reaction đồng thời không tranh `version` của tin |
| Projection | `ChannelRef` | `(channelId, kind SERVER/DM, serverId?, deletedAt?)` — dựng từ `ChannelCreated/ChannelDeleted/ServerDeleted` và DM cục bộ |
| Aggregate root | `FriendRequest` | Máy trạng thái `PENDING → ACCEPTED/DECLINED/CANCELED` ([sơ đồ](../03-diagrams/activity-friend-request-lifecycle.md)) |
| Aggregate root | `Friendship` | Cặp `(userLowId, userHighId)` chuẩn hóa |
| Aggregate root | `DmChannel` | Duy nhất cho mỗi cặp user |
| Value object | `MessageContent`, `Emoji`, `MediaRef`, `Nonce`, `UserPair` | |
| Domain service | `MentionParser`, `DmAccessPolicy` | |
| Port | `ChannelPermissionChecker` (hiện thực: gRPC + Redis cache), `UserDirectory` (gRPC Identity), `UploadSigner` | |
| Domain event | `MessageCreated`, `MessageEdited`, `MessageDeleted`, `ReactionAdded`, `ReactionRemoved`, `FriendRequestSent`, `FriendRequestAccepted`, `FriendRequestDeclined`, `FriendRequestCanceled`, `FriendRemoved`, `DmChannelCreated` | |

| REST | Mô tả | FR |
|------|------|----|
| `GET /api/v1/channels/{channelId}/messages?before=&after=&limit=` | Lịch sử | FR-055 |
| `POST /api/v1/channels/{channelId}/messages` | Gửi | FR-050, FR-053, FR-058, FR-059 |
| `PATCH /api/v1/channels/{channelId}/messages/{messageId}` | Sửa | FR-051 |
| `DELETE /api/v1/channels/{channelId}/messages/{messageId}` | Xóa | FR-052 |
| `PUT/DELETE /api/v1/channels/{channelId}/messages/{messageId}/reactions/{emoji}` | Reaction (emoji URL-encode) | FR-054 |
| `POST /api/v1/attachments/signature` | Chữ ký upload `{channelId}` | FR-101 |
| `GET /api/v1/friends`, `DELETE /api/v1/friends/{userId}` | Bạn bè, hủy kết bạn | FR-073, FR-074 |
| `GET/POST /api/v1/friends/requests` | Lời mời đến/đi, gửi lời mời | FR-070, FR-073 |
| `POST /api/v1/friends/requests/{requestId}/accept`, `/decline`; `DELETE /api/v1/friends/requests/{requestId}` | Phản hồi, hủy | FR-071, FR-072 |
| `GET /api/v1/dms`, `POST /api/v1/dms` | Danh sách DM, mở DM `{friendId}` | FR-075 |

gRPC server: `messaging.v1.ChannelAccessService` — `CheckChannelAccess`; `messaging.v1.SocialService` — `ListFriendIds`.
gRPC client: `community.v1.PermissionService/CheckChannelPermissions`, `identity.v1.UserService/GetUser`.
Nghe: `community.events` (`ChannelCreated`, `ChannelDeleted`, `ServerDeleted` → `channel_refs`; `RoleUpdated`, `RoleDeleted`, `MemberRolesChanged`, `MemberLeft`, `MemberKicked`, `MemberBanned`, `ChannelPermissionsChanged`, `ServerDeleted` → xóa cache quyền).

## 4. presence-service

**Trách nhiệm:** tính online/offline từ các session WebSocket và **quyết định ai được thấy** (D-18: bạn bè ∪ người chung ≥1 server).

| Loại | Tên | Mô tả |
|------|-----|-------|
| Aggregate (Redis) | `UserPresence` | Tập `sessionId`; `ONLINE` nếu ≥1 session |
| Entity | `Session` | `sessionId`, `instanceId`, `connectedAt` |
| Projection (Redis) | `RelationshipProjection` | Tập `serverIds` và `friendIds` của user; dựng từ event, nạp bù lười qua gRPC, tự làm mới sau 24 h |
| Domain service | `PresenceAudiencePolicy` | `canSee(viewer, target)` = cùng người, là bạn, hoặc giao tập server khác rỗng |
| Domain event | `PresenceChanged` | Chỉ phát khi trạng thái thực sự đổi; mang `serverIds[]`, `friendIds[]` để realtime-gateway giao đúng người |

REST: `GET /api/v1/presence?userIds=a,b` (≤200) — chỉ trả user mà người gọi được thấy. gRPC client: `community.v1.PermissionService/ListServerIdsForUser`, `messaging.v1.SocialService/ListFriendIds`. Nghe: `realtime.events` (`SessionConnected`, `SessionDisconnected`, `GatewayHeartbeat`), `community.events` (`MemberJoined`, `MemberLeft`, `MemberKicked`, `MemberBanned`, `ServerDeleted`), `messaging.events` (`FriendRequestAccepted`, `FriendRemoved`). Không có Postgres. Redis key: [data-model.md](data-model.md#5-presence-redis).

## 5. notification-service

**Trách nhiệm:** unread theo kênh, đếm mention.

| Loại | Tên | Mô tả |
|------|-----|-------|
| Aggregate root | `ReadState` | `(userId, channelId)`, `lastReadMessageId` chỉ tăng, `mentionCount ≥ 0` |
| Projection | `ChannelActivity` | `(channelId, serverId?, lastMessageId)` |
| Projection | `ServerMember` | `(serverId, userId)` để kiểm tra mention hợp lệ và tạo read state mốc |
| Entity | `Mention` | Unique `(messageId, userId)` |
| Domain event | `ReadStateUpdated`, `MentionCreated` | |

REST: `GET /api/v1/read-states`, `PUT /api/v1/read-states/{channelId}`. Nghe: `community.events` (`ChannelCreated`, `ChannelDeleted`, `MemberJoined`, `MemberLeft`, `MemberKicked`, `MemberBanned`, `ServerDeleted`), `messaging.events` (`MessageCreated`, `DmChannelCreated`).

## 6. realtime-gateway

**Trách nhiệm:** endpoint `/ws` (STOMP), xác thực CONNECT, kiểm tra quyền khi SUBSCRIBE, đẩy event từ broker tới client, typing. Không có DB, không logic nghiệp vụ. Chi tiết: [realtime-design.md](realtime-design.md).

Phát: `realtime.events` (`SessionConnected`, `SessionDisconnected`, `GatewayHeartbeat`, `TypingStarted`). Nghe: queue riêng mỗi instance bind vào mọi exchange cần đẩy cho client.

## 7. api-gateway

**Trách nhiệm:** định tuyến theo prefix, verify JWT (trừ `/api/v1/auth/**`, `GET /api/v1/invites/{code}`), rate limit (Redis), CORS, gắn `X-Request-Id`, gom OpenAPI. Không có logic nghiệp vụ.

| Prefix | Đích |
|--------|------|
| `/api/v1/auth/**`, `/api/v1/users/**` | identity-service |
| `/api/v1/servers/**`, `/api/v1/invites/**` | community-service |
| `/api/v1/channels/**`, `/api/v1/attachments/**`, `/api/v1/friends/**`, `/api/v1/dms/**` | messaging-service |
| `/api/v1/presence/**` | presence-service |
| `/api/v1/read-states/**` | notification-service |
| `/ws/**` | realtime-gateway (WebSocket) |
