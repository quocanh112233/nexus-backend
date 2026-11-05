# Quy ước API (REST ở biên + WebSocket/STOMP)

File này quy định hình dạng API công khai mà frontend dùng: đường dẫn, xác thực, versioning, định dạng lỗi, phân trang, idempotency và giao thức STOMP. API nội bộ (gRPC) xem [grpc-design.md](grpc-design.md).

## 1. REST chung

| Hạng mục | Quy ước |
|---------|---------|
| Base URL | `http://localhost:8080/api/v1` (qua api-gateway) |
| Định dạng | JSON UTF-8, field `camelCase`, thời gian ISO-8601 UTC, id là chuỗi UUID |
| Tài nguyên | Danh từ số nhiều, lồng tối đa 2 cấp: `/servers/{serverId}/channels/{channelId}` |
| Bản thân | `@me` thay id: `/servers/{serverId}/members/@me` |
| Phương thức | `GET` đọc, `POST` tạo/hành động, `PATCH` sửa một phần, `PUT` thay thế/đặt idempotent, `DELETE` xóa |
| Status | `200` OK, `201` Created (+ `Location`), `204` không body, `400`, `401`, `403`, `404`, `409`, `410`, `422`, `429`, `503` |
| Header | `X-Request-Id` (gateway sinh nếu thiếu, trả lại trong response) |
| Docs | springdoc OpenAPI mỗi service; gateway gom tại `/swagger-ui.html` (P-27) |

## 2. Xác thực

- `Authorization: Bearer <accessToken>` cho mọi endpoint trừ: `POST /auth/register|login|refresh|logout`, `GET /invites/{code}`.
- Refresh token chỉ ở cookie `nexus_rt` (`HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`).
- Claim JWT: `sub` (userId), `username`, `iat`, `exp`, `iss=nexus-identity`, `aud=nexus`, `jti`.

## 3. Versioning

Version trong đường dẫn (`/api/v1`). Thay đổi phá vỡ → `/api/v2` cho tài nguyên đó. Thêm field là tương thích; client phải bỏ qua field lạ.

## 4. Định dạng lỗi

Theo RFC 9457 (Problem Details), thêm `code` ổn định để frontend xử lý:

```json
{
  "type": "https://nexus.dev/errors/permission-denied",
  "title": "Permission denied",
  "status": 403,
  "code": "PERMISSION_DENIED",
  "detail": "Missing SEND_MESSAGES in channel",
  "instance": "/api/v1/channels/0192.../messages",
  "requestId": "b1c2...",
  "errors": [ { "field": "content", "message": "must not be blank" } ]
}
```

| `code` | HTTP | Khi nào |
|--------|------|---------|
| `VALIDATION_FAILED` | 400 | Body/param sai (kèm `errors[]`) |
| `UNAUTHENTICATED` | 401 | Thiếu/sai token |
| `REFRESH_TOKEN_REUSED` | 401 | Phát hiện reuse (FR-003) |
| `PERMISSION_DENIED` | 403 | Thiếu quyền |
| `NOT_FOUND` | 404 | Không tồn tại hoặc không được thấy |
| `USERNAME_TAKEN`, `EMAIL_TAKEN` | 409 | Đăng ký trùng |
| `VERSION_CONFLICT` | 409 | Optimistic lock |
| `ALREADY_FRIENDS`, `FRIEND_REQUEST_EXISTS` | 409 | Kết bạn |
| `INVITE_INVALID` | 410 | Invite hết hạn/hết lượt/thu hồi |
| `BANNED_FROM_SERVER` | 403 | Join server đang bị ban (FR-037) |
| `RATE_LIMITED` | 429 | Kèm `Retry-After` |
| `DEPENDENCY_UNAVAILABLE` | 503 | gRPC callee lỗi/timeout |

Hiện thực chung: `@RestControllerAdvice` trong `libs/nexus-common-web`.

## 5. Phân trang

| Kiểu | Dùng cho | Tham số | Response |
|------|---------|---------|----------|
| Keyset theo id | Tin nhắn | `before`, `after` (messageId), `limit` (1-100, mặc định 50) | Mảng, sắp `id` giảm dần (`before`) hoặc tăng dần (`after`) |
| Cursor mờ | Member, friend, invite | `cursor`, `limit` | `{ "items": [...], "nextCursor": "..." }` (`null` = hết) |

Không dùng `OFFSET` cho danh sách lớn.

## 6. Idempotency

| Thao tác | Cơ chế |
|---------|--------|
| Gửi tin | `nonce` trong body, unique `(author_id, nonce)` → gửi lại trả tin cũ (`200`) |
| Reaction | `PUT` idempotent theo khóa |
| Tham gia server | Đã là member → trả server |
| Mở DM | Tìm hoặc tạo |

## 7. WebSocket / STOMP

| Hạng mục | Giá trị |
|---------|---------|
| Endpoint | `ws://localhost:8080/ws` (gateway → realtime-gateway) |
| Xác thực | Header STOMP `CONNECT`: `Authorization: Bearer <accessToken>`; token hết hạn → client reconnect với token mới |
| Heartbeat | `10000,10000` |

**Client SUBSCRIBE:**

| Destination | Nội dung | Kiểm tra khi subscribe |
|-------------|---------|------------------------|
| `/topic/channels/{channelId}` | Tin nhắn, sửa, xóa, reaction, typing của kênh | gRPC `CheckChannelAccess` (`can_view`) |
| `/topic/servers/{serverId}` | Category, kênh, role, member (kể cả kick/ban), server, `PresenceChanged` của member | gRPC `CheckServerMembership` |
| `/user/queue/events` | Friend request, DM mới, read state, mention, quyền của tôi đổi, tôi bị kick/ban, `PresenceChanged` của bạn bè | Chỉ chính user |

Không có topic presence chung: presence chỉ tới bạn bè và người chung server (D-18).

**Client SEND:**

| Destination | Body | Ghi chú |
|-------------|------|---------|
| `/app/channels/{channelId}/typing` | `{}` | Tối đa 1 lần/3 s/kênh; gửi tin nhắn vẫn dùng REST |

**Frame server gửi:**

```json
{ "type": "MessageCreated", "eventId": "...", "occurredAt": "...", "data": { "...": "payload như event-catalog" } }
```

`type` trùng tên event trong [event-catalog.md](event-catalog.md); client bỏ qua `type` không biết.
