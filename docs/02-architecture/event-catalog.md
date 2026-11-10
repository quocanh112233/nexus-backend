# Event catalog

File này là danh mục chuẩn của mọi integration event: tên, producer, consumer, routing key, payload chính. Đây là "published language" bất đồng bộ của Nexus — đổi ở đây trước khi đổi code. Cơ chế broker: [messaging-deep-dive.md](messaging-deep-dive.md).

## 1. Quy ước

| Hạng mục | Quy ước | Ví dụ |
|---------|---------|-------|
| Tên event | PascalCase, thì quá khứ | `MessageCreated` |
| Exchange | Một **topic exchange** cho mỗi producer: `<producer>.events`, durable | `messaging.events` |
| Routing key | `<aggregate>.<hành-động>` kebab-case | `message.created`, `friend-request.sent` |
| Queue consumer | `<consumer>.<producer>.events`, quorum queue, durable | `notification.messaging.events` |
| DLQ | `<queue>.dlq`, qua exchange `nexus.dlx` (direct) | `notification.messaging.events.dlq` |
| Queue realtime-gateway | `realtime-gateway.<instanceId>`, classic, exclusive, auto-delete | `realtime-gateway.rt-7f3a` |
| Phiên bản | `eventVersion` (số nguyên) trong envelope; thay đổi không tương thích → tăng version, phát song song một thời gian | |
| Giao nhận | At-least-once; consumer idempotent theo `eventId` | |

### Envelope (JSON, `content_type: application/json`)

```json
{
  "eventId": "0192f0c4-7a1e-7c3d-9a1b-1f2e3d4c5b6a",
  "eventType": "MessageCreated",
  "eventVersion": 1,
  "occurredAt": "2026-10-23T10:15:30.123Z",
  "producer": "messaging-service",
  "aggregateType": "Message",
  "aggregateId": "0192f0c4-7a1e-7c3d-9a1b-000000000001",
  "payload": { }
}
```

`eventId` cũng đặt vào AMQP property `message_id`. `traceparent` (W3C) không nằm trong body mà đi bằng header AMQP `traceparent` để OpenTelemetry nối trace; envelope cũng không có `aggregateVersion` (bảng outbox không có cột này).

## 2. identity.events

| Event | Routing key | Consumer | Payload chính |
|-------|-------------|----------|---------------|
| `UserRegistered` | `user.registered` | (chưa có consumer bắt buộc; dùng để quan sát/học) | `userId, username, displayName` |
| `UserProfileUpdated` | `user.profile-updated` | (chưa có consumer ở MVP; frontend làm mới cache profile theo TTL 5 phút) | `userId, displayName, avatarUrl, aboutMe` |

## 3. community.events

| Event | Routing key | Consumer | Payload chính |
|-------|-------------|----------|---------------|
| `ServerCreated` | `server.created` | realtime-gateway | `serverId, name, ownerId` |
| `ServerUpdated` | `server.updated` | realtime-gateway | `serverId, name` |
| `ServerDeleted` | `server.deleted` | messaging, notification, presence, realtime-gateway | `serverId` |
| `CategoryCreated` | `category.created` | realtime-gateway | `serverId, categoryId, name, position` |
| `CategoryUpdated` | `category.updated` | realtime-gateway | `serverId, categoryId, name, position` |
| `CategoryDeleted` | `category.deleted` | realtime-gateway | `serverId, categoryId` |
| `ChannelCreated` | `channel.created` | messaging, notification, realtime-gateway | `serverId, channelId, categoryId?, name, position` |
| `ChannelUpdated` | `channel.updated` | realtime-gateway | `serverId, channelId, categoryId?, name, topic, position` |
| `ChannelDeleted` | `channel.deleted` | messaging, notification, realtime-gateway | `serverId, channelId` |
| `ChannelPermissionsChanged` | `channel.permissions-changed` | messaging, realtime-gateway | `serverId, channelId` |
| `MemberJoined` | `member.joined` | notification, presence, realtime-gateway | `serverId, userId, joinedAt` |
| `MemberLeft` | `member.left` | messaging, notification, presence, realtime-gateway | `serverId, userId` |
| `MemberKicked` | `member.kicked` | messaging, notification, presence, realtime-gateway | `serverId, userId, kickedBy` (realtime-gateway bỏ `kickedBy` trên `/topic/servers/{id}`, giữ nguyên trên `/user/queue/events` của người bị kick) |
| `MemberBanned` | `member.banned` | messaging, notification, presence, realtime-gateway | `serverId, userId, bannedBy, reason?, wasMember` |
| `MemberUnbanned` | `member.unbanned` | realtime-gateway | `serverId, userId, unbannedBy` |
| `MemberRolesChanged` | `member.roles-changed` | messaging, realtime-gateway | `serverId, userId, roleIds[]` |
| `RoleCreated` | `role.created` | realtime-gateway | `serverId, roleId, name, permissions, position` |
| `RoleUpdated` | `role.updated` | messaging, realtime-gateway | `serverId, roleId, name, permissions, position` |
| `RoleDeleted` | `role.deleted` | messaging, realtime-gateway | `serverId, roleId` |

Ghi chú: `permissions` là chuỗi số thập phân của bitset (tránh mất chính xác `long` trong JavaScript).

## 4. messaging.events

| Event | Routing key | Consumer | Payload chính |
|-------|-------------|----------|---------------|
| `MessageCreated` | `message.created` | notification, realtime-gateway | `messageId, channelId, serverId? (null với DM), authorId, content, replyToId?, attachments[{publicId,url,contentType,bytes}], mentionUserIds[], createdAt, nonce` |
| `MessageEdited` | `message.edited` | realtime-gateway | `messageId, channelId, serverId? (null với DM), content, mentionUserIds[], editedAt, version` |
| `MessageDeleted` | `message.deleted` | realtime-gateway | `messageId, channelId, serverId? (null với DM), deletedBy` |
| `ReactionAdded` | `reaction.added` | realtime-gateway | `messageId, channelId, userId, emoji` |
| `ReactionRemoved` | `reaction.removed` | realtime-gateway | `messageId, channelId, userId, emoji` |
| `FriendRequestSent` | `friend-request.sent` | realtime-gateway | `requestId, requesterId, addresseeId` |
| `FriendRequestAccepted` | `friend-request.accepted` | presence, realtime-gateway | `requestId, requesterId, addresseeId` |
| `FriendRequestDeclined` | `friend-request.declined` | realtime-gateway | `requestId, requesterId, addresseeId` |
| `FriendRequestCanceled` | `friend-request.canceled` | realtime-gateway | `requestId, requesterId, addresseeId` |
| `FriendRemoved` | `friend.removed` | presence, realtime-gateway | `userId, friendId` |
| `DmChannelCreated` | `dm-channel.created` | notification, realtime-gateway | `channelId, participantIds[2]` |

## 5. presence.events

| Event | Routing key | Consumer | Payload chính |
|-------|-------------|----------|---------------|
| `PresenceChanged` | `presence.changed` | realtime-gateway | `userId, status (ONLINE/OFFLINE), changedAt, serverIds[], friendIds[]` — danh sách người nhận hợp lệ theo D-18; realtime-gateway **chỉ** giao tới `/topic/servers/{serverId}` của các `serverIds` và `/user/queue/events` của các `friendIds` |

Ghi chú triển khai P7: `friendIds` là toàn bộ bạn trong projection (rỗng khi nạp quan hệ lỗi, fail-closed), frame tới bạn ở `/user/queue/events`; người vừa là bạn vừa chung server có thể nhận frame ở cả `/topic/servers/{id}` và `/user/queue/events`. Queue `presence.messaging.events` nhận `friend-request.accepted` và `friend.removed` để cập nhật projection bạn bè. Frame gửi cho client chỉ mang `userId, status, changedAt` (bỏ `serverIds`, `friendIds`). Cùng nguyên tắc cho `MemberBanned` trên `/topic/servers/{id}`: frame chỉ có `serverId, userId, wasMember`, không có `reason`, `bannedBy`; bản gửi cho chính user bị ban ở `/user/queue/events` giữ nguyên payload.

## 6. realtime.events

| Event | Routing key | Consumer | Payload chính | Ghi chú |
|-------|-------------|----------|---------------|---------|
| `SessionConnected` | `session.connected` | presence | `userId, sessionId, instanceId, connectedAt` | Phát thẳng (không outbox, gateway không có DB) — mất event được bù bởi heartbeat |
| `SessionDisconnected` | `session.disconnected` | presence | `userId, sessionId, instanceId` | Như trên |
| `GatewayHeartbeat` | `gateway.heartbeat` | presence | `instanceId, sessionIds[], userIds[], sentAt` | 30 s/lần; `userIds[]` song song với `sessionIds[]` (cùng chỉ số) để Presence nối lại session bị mất `SessionConnected`; kích thước không giới hạn theo số session của instance |
| `TypingStarted` | `typing.started` | realtime-gateway (mọi instance) | `channelId, userId, startedAt` | Non-persistent, TTL message 5 s (P-06); mất do broker/TTL chấp nhận được vì chỉ là gợi ý UI |

## 7. notification.events

| Event | Routing key | Consumer | Payload chính |
|-------|-------------|----------|---------------|
| `ReadStateUpdated` | `read-state.updated` | realtime-gateway | `userId, channelId, lastReadMessageId, mentionCount` |
| `MentionCreated` | `mention.created` | realtime-gateway | `userId, channelId, serverId?, messageId, authorId, mentionCount` |

## 8. Ma trận binding (queue ← exchange, routing key)

| Queue | Exchange | Routing key bind |
|-------|----------|------------------|
| `messaging.community.events` | `community.events` | `channel.created`, `channel.deleted`, `channel.permissions-changed`, `server.deleted`, `member.left`, `member.kicked`, `member.banned`, `member.roles-changed`, `role.updated`, `role.deleted` |
| `notification.community.events` (P8, chưa có) | `community.events` | `channel.created`, `channel.deleted`, `member.joined`, `member.left`, `member.kicked`, `member.banned`, `server.deleted` |
| `presence.community.events` | `community.events` | `member.joined`, `member.left`, `member.kicked`, `member.banned`, `server.deleted` |
| `presence.messaging.events` (P7) | `messaging.events` | `friend-request.accepted`, `friend.removed` |
| `notification.messaging.events` (P8, chưa có) | `messaging.events` | `message.created`, `dm-channel.created` |
| `presence.realtime.events` | `realtime.events` | `session.*`, `gateway.heartbeat` |
| `realtime-gateway.<instanceId>` | `community.events` | `#` |
| | `messaging.events` | `#` |
| | `presence.events` | `presence.changed` |
| | `notification.events` (P8, chưa có) | `#` |
| | `realtime.events` | `typing.started` |

Ai khai báo gì: **producer** khai báo exchange của mình; **consumer** khai báo queue, DLQ và binding của mình (consumer sở hữu queue). File `infra/rabbitmq/definitions.json` chỉ dùng để tạo vhost `nexus` và user mỗi service.

## 9. Kiểm tra hợp đồng

Mỗi event có file mẫu `contracts/events/<EventType>.json` (chỉ phần `payload`). Producer test: serialize event → so khớp cấu trúc với file mẫu. Consumer test: deserialize file mẫu → không lỗi, map đúng. Xem [testing-strategy.md](../05-plan/testing-strategy.md).
