# Workflow nghiệp vụ chính

File này mô tả từng bước các luồng nghiệp vụ quan trọng, ghi rõ bước nào **đồng bộ** (REST/gRPC) và bước nào **bất đồng bộ** (event qua RabbitMQ). Sơ đồ chi tiết ở [03-diagrams](../03-diagrams/).

Ký hiệu: `[REST]` frontend → API Gateway → service; `[gRPC]` service → service; `[EVT]` event qua RabbitMQ; `[WS]` WebSocket/STOMP.

## WF-01 Đăng ký (FR-001)
1. `[REST]` `POST /api/v1/auth/register {email, username, password}` → Identity.
2. Identity validate, chuẩn hóa email/username (lowercase), kiểm tra trùng (unique index), hash BCrypt.
3. Trong **một transaction**: insert `users` + insert `outbox_event(UserRegistered)`.
4. Trả `201` + public profile. Frontend chuyển sang đăng nhập (hoặc tự đăng nhập).
5. `[EVT]` Outbox relay phát `UserRegistered` (chưa có consumer bắt buộc ở MVP; dùng để quan sát luồng event).
Lỗi: trùng email/username → `409 CONFLICT` (`code = USERNAME_TAKEN | EMAIL_TAKEN`).
Sơ đồ: [activity-registration.md](../03-diagrams/activity-registration.md).

## WF-02 Đăng nhập và làm mới token (FR-002, FR-003, FR-004)
1. `[REST]` `POST /api/v1/auth/login` → Identity kiểm tra password.
2. Tạo refresh token ngẫu nhiên 256-bit, lưu SHA-256 + `family_id` mới; ký access token JWT (RS256, 15 phút).
3. Trả `{accessToken, expiresIn}` + `Set-Cookie: nexus_rt=...; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`.
4. Frontend giữ access token trong bộ nhớ (Zustand, không localStorage).
5. Hết hạn → `[REST]` `POST /api/v1/auth/refresh` (cookie tự gửi) → Identity: token hợp lệ → đánh dấu `revoked`, `replaced_by` = token mới (rotation). Token đã revoke bị dùng lại → **thu hồi cả family** (nghi bị đánh cắp) → 401.
6. Logout: `POST /api/v1/auth/logout` → revoke token, xóa cookie.
Sơ đồ: [sequence-login-refresh.md](../03-diagrams/sequence-login-refresh.md).

## WF-03 Tạo server (FR-020)
1. `[REST]` `POST /api/v1/servers {name}` → Community.
2. Aggregate `Server` tạo: role `@everyone` (quyền mặc định), category "Text Channels", kênh `general`, member owner.
3. Một transaction: lưu tất cả + outbox `ServerCreated`, `CategoryCreated`, `ChannelCreated`, `MemberJoined`.
4. Trả `201` với server + channels + roles.
5. `[EVT]` Messaging tạo `channel_refs`; Notification tạo `channel_activity`, `server_members`, `read_states`; realtime-gateway đẩy tới client đang subscribe.
Sơ đồ: [sequence-create-server.md](../03-diagrams/sequence-create-server.md).

## WF-04 Mời và tham gia server (FR-026..FR-028)
1. Member có `CREATE_INVITE`: `[REST]` `POST /api/v1/servers/{serverId}/invites {maxUses, expiresInSeconds}` → trả `code` (8 ký tự base62). Link: `/invite/{code}`.
2. Người nhận mở link: `[REST]` `GET /api/v1/invites/{code}` → preview (không cần là member).
3. `[REST]` `POST /api/v1/invites/{code}/accept` → Community:
   - Đã là member → trả server (idempotent, không tăng `uses`).
   - Có dòng trong `server_bans(server_id, user_id)` → `403 BANNED_FROM_SERVER` (kiểm tra trong cùng transaction, không cần RPC — P-35).
   - `UPDATE invites SET uses = uses + 1 WHERE code = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now()) AND (max_uses = 0 OR uses < max_uses)` — 0 dòng → `410 INVITE_INVALID`. Câu lệnh nguyên tử chống vượt `maxUses` khi nhiều người join cùng lúc.
   - Insert `members` (PK `(server_id, user_id)` chặn trùng) + outbox `MemberJoined` trong cùng transaction.
4. `[EVT]` `MemberJoined` → Notification (thêm `server_members`, tạo `read_states` mốc), Presence (thêm server vào projection quan hệ), realtime-gateway (đẩy vào `/topic/servers/{serverId}`).
Sơ đồ: [sequence-join-server-invite.md](../03-diagrams/sequence-join-server-invite.md).

## WF-05 Gửi tin nhắn (FR-050, FR-056, FR-059)
1. `[REST]` `POST /api/v1/channels/{channelId}/messages {content, nonce, replyToId?, attachments?[]}` → Messaging.
2. Messaging tra `channel_refs`: kênh DM → tự kiểm tra quyền (participant + còn là bạn). Kênh server → đọc cache Redis; miss → `[gRPC]` `community.v1.PermissionService/CheckChannelPermissions` (deadline 300 ms) → lưu cache.
3. Thiếu quyền → `403`. Community không trả lời (timeout/circuit mở) → `503 DEPENDENCY_UNAVAILABLE` (fail-closed: không gửi tin khi không chắc quyền).
4. Kiểm tra `nonce` (unique `(author_id, nonce)`): trùng → trả tin đã tạo (idempotent khi client retry).
5. Parse mention `<@uuid>`; validate `replyToId` cùng kênh; validate attachments (FR-102).
6. Một transaction: insert `messages`, `message_attachments`, `message_mentions`, outbox `MessageCreated`. Trả `201`.
7. `[EVT]` `MessageCreated` → realtime-gateway (mọi instance) đẩy `/topic/channels/{channelId}`; Notification cập nhật `channel_activity`, tăng `mention_count` cho người được mention.
Sơ đồ: [sequence-send-message.md](../03-diagrams/sequence-send-message.md), kiểm tra quyền: [activity-permission-check.md](../03-diagrams/activity-permission-check.md).

## WF-06 Sửa / xóa tin, reaction (FR-051, FR-052, FR-054)
- Sửa: chỉ tác giả; optimistic locking cột `version` (hai tab sửa cùng lúc → một bên `409`); outbox `MessageEdited {version}`.
- Xóa: tác giả hoặc `MANAGE_MESSAGES` (gRPC check); xóa mềm `deleted_at`, xóa nội dung; outbox `MessageDeleted`.
- Reaction: `INSERT ... ON CONFLICT DO NOTHING` trên PK `(message_id, user_id, emoji)` → bấm đúp không tạo trùng; chỉ phát `ReactionAdded` khi thật sự insert.
- Consumer realtime bỏ qua event có `version` cũ hơn bản client đang giữ (chống đảo thứ tự).

## WF-07 Kết bạn và mở DM (FR-070..FR-075)
1. Frontend tra username: `[REST]` `GET /api/v1/users/by-username/{username}` → Identity.
2. `[REST]` `POST /api/v1/friends/requests {targetUserId}` → Messaging. Messaging `[gRPC]` `identity.v1.UserService/GetUser` để chắc user tồn tại (deadline 300 ms).
3. Nếu target đã gửi lời mời ngược lại đang `PENDING` → tự chấp nhận (tạo `friendships`, `FriendRequestAccepted`). Ngược lại tạo `friend_requests(PENDING)` + `FriendRequestSent`.
4. Người nhận `POST /api/v1/friends/requests/{id}/accept` hoặc `/decline`; người gửi `DELETE /api/v1/friends/requests/{id}` (hủy).
5. Accept: một transaction cập nhật request + insert `friendships(user_low_id, user_high_id)` + outbox `FriendRequestAccepted`.
6. Mở DM: `POST /api/v1/dms {friendId}` → kiểm tra là bạn → tìm hoặc tạo `dm_channels` (unique cặp user) + `channel_refs(kind=DM)` + outbox `DmChannelCreated` (lần đầu).
7. `[EVT]` realtime-gateway đẩy tới `/user/queue/events` của cả hai; Notification tạo `channel_activity` + `read_states` cho DM.
Sơ đồ: [sequence-friend-request.md](../03-diagrams/sequence-friend-request.md), [activity-friend-request-lifecycle.md](../03-diagrams/activity-friend-request-lifecycle.md).

## WF-08 Đổi role của member (FR-033)
1. `[REST]` `PUT /api/v1/servers/{serverId}/members/{userId}/roles {roleIds[]}` → Community.
2. Kiểm tra actor có `MANAGE_ROLES` và mọi role thêm/bớt đều **thấp hơn** role cao nhất của actor (owner bỏ qua).
3. Một transaction: cập nhật `member_roles` + outbox `MemberRolesChanged`.
4. `[EVT]` Messaging tăng version cache quyền của server (cache cũ tự hết hiệu lực); realtime-gateway kiểm tra lại subscription của user đó và đẩy event cho client cập nhật UI.

## WF-09 Upload file qua Cloudinary (FR-100..FR-102)
1. `[REST]` xin chữ ký: avatar `POST /api/v1/users/me/avatar/signature` (Identity) hoặc đính kèm `POST /api/v1/attachments/signature {channelId}` (Messaging, cần `ATTACH_FILES`).
2. Backend trả `{cloudName, apiKey, timestamp, folder, signature, allowedFormats, maxBytes}`.
3. Frontend upload thẳng `https://api.cloudinary.com/v1_1/{cloudName}/auto/upload`.
4. Frontend gửi `publicId` cho backend (`PUT /api/v1/users/me/avatar` hoặc trong body gửi tin).
5. Backend xác minh `publicId` bắt đầu bằng folder đã cấp cho user + gọi Cloudinary Admin API lấy metadata (loại, kích thước) → lưu.
Sơ đồ: [sequence-upload-cloudinary.md](../03-diagrams/sequence-upload-cloudinary.md).

## WF-10 Presence (FR-080, FR-081)
1. Client mở WebSocket `[WS]` `/ws` (qua API Gateway) và gửi STOMP `CONNECT` kèm JWT.
2. realtime-gateway xác thực → `[EVT]` `SessionConnected {userId, sessionId, instanceId}`.
3. Presence: đảm bảo projection quan hệ của user đã nạp (chưa có → `[gRPC]` `community.v1.PermissionService/ListServerIdsForUser` và `messaging.v1.SocialService/ListFriendIds`); thêm session vào Redis; nếu user chuyển 0 → 1 session → `[EVT]` `PresenceChanged(ONLINE, serverIds, friendIds)`.
4. Ngắt kết nối → `SessionDisconnected` → nếu còn 0 session → `PresenceChanged(OFFLINE, ...)`.
5. Mỗi 30 s mỗi instance gateway phát `GatewayHeartbeat {instanceId, sessionIds}`; instance im lặng > 60 s (job dọn mỗi 15 s, tối đa 75 s kể từ lúc chết) → Presence xóa toàn bộ session của instance đó (gateway chết đột ngột).
6. realtime-gateway đẩy `PresenceChanged` tới `/topic/servers/{serverId}` cho mỗi `serverId` (chỉ member subscribe được) và `/user/queue/events` của mỗi `friendId` → **chỉ bạn bè và người chung server nhận** (D-18, P-14).
7. Trạng thái ban đầu: client gọi `GET /api/v1/presence?userIds=`; Presence chỉ trả trạng thái của user là chính mình, bạn bè hoặc chung server với người gọi; user khác bị bỏ khỏi kết quả.
8. Quan hệ đổi (`MemberJoined/Left/Kicked/Banned`, `ServerDeleted`, `FriendRequestAccepted`, `FriendRemoved`) → Presence cập nhật projection; client nhận event tương ứng thì tải lại presence cho danh sách đang hiển thị.
Sơ đồ: [sequence-presence.md](../03-diagrams/sequence-presence.md).

## WF-11 Đọc tin, unread và mention (FR-090..FR-093)
1. Khi mở app: `[REST]` `GET /api/v1/read-states` → Notification trả danh sách `{channelId, lastReadMessageId, lastMessageId, mentionCount}`.
2. Khi đọc tới cuối kênh: `PUT /api/v1/read-states/{channelId} {lastReadMessageId}` → cập nhật `GREATEST(...)` (không lùi), `mention_count = 0` → outbox `ReadStateUpdated` → realtime đẩy tới các thiết bị khác của cùng user.
3. `MessageCreated` có mention → Notification kiểm tra người được mention là member (`server_members`) hoặc participant DM → tăng `mention_count`, insert `mentions` (unique `(message_id, user_id)` chống trùng) → outbox `MentionCreated`.

## WF-12 Kick / ban / gỡ ban (FR-036, FR-037, FR-039)
1. Kick: `[REST]` `DELETE /api/v1/servers/{serverId}/members/{userId}` → Community. Ban: `PUT /api/v1/servers/{serverId}/bans/{userId} {reason?}`. Gỡ ban: `DELETE /api/v1/servers/{serverId}/bans/{userId}`. Danh sách: `GET /api/v1/servers/{serverId}/bans`.
2. Community kiểm tra `KICK_MEMBERS` / `BAN_MEMBERS` + luật thứ bậc (`RoleHierarchyPolicy`): không áp dụng lên owner, chính mình, hay member có role cao nhất ≥ của actor → `403`.
3. Một transaction: kick → xóa `members` (+ `member_roles` cascade) + outbox `MemberKicked`; ban → upsert `server_bans` + xóa `members` nếu có + outbox `MemberBanned {wasMember}`; gỡ ban → xóa `server_bans` + outbox `MemberUnbanned`.
4. Ban đồng thời với join qua invite (race): join kiểm "chưa bị ban" rồi insert member, ban insert ban rồi xóa member — hai transaction xen kẽ có thể để lại *member đã bị ban*. Cách chặn: cả join và ban/gỡ ban đều gọi `SELECT pg_advisory_xact_lock(hashtextextended(:serverId || ':' || :userId, 0))` đầu transaction → tuần tự hóa theo cặp `(server, user)`, không khóa cả bảng (IT bắt buộc ở P3).
5. `[EVT]` `MemberKicked` / `MemberBanned` → Messaging (tăng version cache quyền), Notification (xóa `server_members`, `read_states` của server), Presence (bỏ server khỏi projection), realtime-gateway (hủy subscription của user đó trong server, đẩy `/user/queue/events` `{type: MemberKicked|MemberBanned}` cho người bị xử lý và `/topic/servers/{serverId}` cho member còn lại).
6. Tin nhắn cũ của người bị kick/ban **giữ nguyên** (xóa hàng loạt là backlog).
Sơ đồ: [sequence-kick-ban.md](../03-diagrams/sequence-kick-ban.md), [activity-kick-ban-check.md](../03-diagrams/activity-kick-ban-check.md).
