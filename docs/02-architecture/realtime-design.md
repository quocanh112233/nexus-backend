# Thiết kế realtime

File này mô tả `realtime-gateway`: cách client kết nối, cách event từ service tới đúng client khi chạy nhiều instance, typing và presence. Destination STOMP: [api-conventions.md](api-conventions.md#7-websocket--stomp).

## 1. Nguyên tắc

- `realtime-gateway` **không có logic nghiệp vụ và không có DB**. Nó chỉ: xác thực, kiểm tra quyền khi subscribe (gRPC), chuyển event từ RabbitMQ thành frame STOMP.
- Client **gửi tin bằng REST**, nhận bằng WebSocket. Lý do: REST có status code, idempotency (`nonce`), retry rõ ràng; WebSocket chỉ là kênh đẩy.

## 2. Kiến trúc một instance

| Thành phần trong gateway | Vai trò |
|-------------------------|---------|
| STOMP endpoint `/ws` (Spring WebSocket) | Nhận kết nối |
| `StompAuthChannelInterceptor` | `CONNECT`: verify JWT (JWKS); `SUBSCRIBE`: gọi gRPC kiểm tra quyền, từ chối → frame `ERROR` |
| Simple broker trong bộ nhớ | Giữ map destination → session của **instance này** |
| `SubscriptionRegistry` | Ghi `(sessionId, userId, destination)` để thu hồi khi quyền đổi |
| `BrokerEventListener` | Nghe queue riêng `realtime-gateway.<instanceId>`, map event → destination, gọi `SimpMessagingTemplate` |
| `PresenceReporter` | Phát `SessionConnected/Disconnected`, `GatewayHeartbeat` mỗi 30 s |

## 3. Scale nhiều instance (P-13)

Vấn đề: user A nối instance 1, user B nối instance 2, cùng kênh. Event `MessageCreated` phải tới cả hai.

Giải pháp: **mỗi instance có một queue riêng** (`realtime-gateway.<instanceId>`, exclusive, auto-delete) bind vào các exchange. Mỗi event được RabbitMQ **nhân bản** vào queue của mọi instance (fan-out). Mỗi instance chỉ đẩy cho session cục bộ đang subscribe destination tương ứng; instance không có ai subscribe thì bỏ qua (chi phí nhỏ).

| Event | Destination |
|-------|-------------|
| `MessageCreated`, `MessageEdited`, `MessageDeleted`, `ReactionAdded`, `ReactionRemoved`, `TypingStarted` | `/topic/channels/{channelId}` |
| `ServerUpdated`, `ServerDeleted`, `Category*`, `ChannelCreated`, `ChannelUpdated`, `ChannelDeleted`, `ChannelPermissionsChanged`, `Role*`, `MemberJoined`, `MemberLeft`, `MemberKicked`, `MemberBanned`, `MemberUnbanned`, `MemberRolesChanged` | `/topic/servers/{serverId}` |
| `MemberKicked`, `MemberBanned` | thêm `/user/queue/events` của người bị xử lý (UI bỏ server, thông báo) |
| `PresenceChanged` | `/topic/servers/{serverId}` với mỗi `serverId` trong payload **và** `/user/queue/events` của mỗi `friendId` trong payload — không có topic presence chung (D-18) |
| `FriendRequest*`, `FriendRemoved` | `/user/queue/events` của requester và addressee (hoặc 2 user) |
| `DmChannelCreated` | `/user/queue/events` của 2 participant |
| `ReadStateUpdated`, `MentionCreated` | `/user/queue/events` của `userId` |
| `MemberJoined` | thêm `/user/queue/events` của user vừa join (để UI thêm server) |
| `ServerCreated` | `/user/queue/events` của owner (thiết bị khác) |

Không cần sticky session: client reconnect vào instance nào cũng được, subscribe lại.

Ceiling (ghi rõ): mọi instance nhận mọi event → khi rất nhiều instance/event, chuyển sang routing theo destination (bind theo `channelId`) hoặc STOMP broker relay. Không cần cho phạm vi Nexus.

## 4. Thu hồi quyền khi đang subscribe

Khi nhận `MemberLeft`, `MemberKicked`, `MemberBanned`, `MemberRolesChanged`, `ChannelPermissionsChanged`, `RoleUpdated`, `RoleDeleted`, `ServerDeleted`, `ChannelDeleted`, `FriendRemoved`: gateway tìm trong `SubscriptionRegistry` các subscription liên quan trên instance của nó, gọi lại gRPC kiểm tra; không còn quyền → hủy subscription và gửi `/user/queue/events` `{type: "SubscriptionRevoked", destination}`. Cửa sổ rò rỉ: vài trăm ms (chấp nhận, ghi trong rủi ro).

## 5. Typing (P-06)

1. Client SEND `/app/channels/{channelId}/typing` (throttle 3 s ở client, server chặn thêm).
2. Gateway kiểm tra quyền gửi bằng cache kết quả `CheckChannelAccess` của subscription (không gọi gRPC mỗi lần).
3. Gateway publish `TypingStarted` (non-persistent, TTL 5 s) lên `realtime.events` → mọi instance nhận → đẩy `/topic/channels/{channelId}`.
4. Client hiện "X đang gõ" 8 s, tắt sớm khi nhận `MessageCreated` của X.

Không lưu, không qua Presence, mất cũng không sao (at-most-once).

## 6. Presence và quyền riêng tư

**Quy tắc (D-18, đã chốt):** chỉ **bạn bè** và **người chung ≥ 1 server** thấy trạng thái online. Người lạ không thấy. Luồng: [WF-10](../01-requirements/workflows.md#wf-10-presence-fr-080-fr-081), [sequence-presence.md](../03-diagrams/sequence-presence.md).

**Cách hiện thực (đề xuất P-14):**

| Bước | Ai làm | Chi tiết |
|------|-------|---------|
| 1. Biết quan hệ | presence-service | Projection Redis `presence:rel:{userId}:servers` (từ `MemberJoined/Left/Kicked/Banned`, `ServerDeleted`) và `presence:rel:{userId}:friends` (từ `FriendRequestAccepted`, `FriendRemoved`). Chưa có hoặc hết hạn 24 h → nạp bù bằng gRPC `community.v1.PermissionService/ListServerIdsForUser` + `messaging.v1.SocialService/ListFriendIds` |
| 2. Đóng gói người nhận | presence-service | `PresenceChanged` mang `serverIds[]` + `friendIds[]` của user đổi trạng thái |
| 3. Giao | realtime-gateway | Gửi vào `/topic/servers/{serverId}` (chỉ member subscribe được, đã kiểm bằng `CheckServerMembership`) và `/user/queue/events` của từng bạn. Client gộp trùng theo `userId` |
| 4. Trạng thái ban đầu | presence-service | `GET /api/v1/presence?userIds=` lọc bằng `PresenceAudiencePolicy.canSee` — user không được thấy bị bỏ khỏi kết quả (không trả `OFFLINE` giả để tránh nhầm lẫn) |
| 5. Quan hệ đổi | presence-service + client | Projection cập nhật theo event; realtime-gateway thu hồi subscription `/topic/servers/{id}` khi bị kick/ban/rời (mục 4) nên ngừng nhận presence của server đó ngay |

**Chi phí / độ phức tạp:**

| Khía cạnh | Đánh giá |
|----------|---------|
| Mỗi lần đổi trạng thái | O(số server + số bạn) message STOMP mỗi instance, **không** O(tổng số member) — vì giao theo topic server |
| `GET /presence` 200 id | 200 phép `SISMEMBER` + `SINTERCARD` trong 1 pipeline Redis, ~ms |
| Bộ nhớ Redis | 2 set nhỏ mỗi user đã nạp |
| Độ phức tạp code | Thêm 2 consumer binding, 2 RPC, 1 policy — vừa sức, và là bài học thật về projection + nạp bù |
| Sai lệch projection | Event mất/đến trễ → tự sửa khi `loaded` hết hạn 24 h; kick/ban/rời còn được chặn thêm ở tầng subscription |

**Cách đơn giản hơn cho phase đầu (vẫn đúng quy tắc riêng tư):** ở P6 chỉ giao qua `/topic/servers/{serverId}` (người chung server) và lọc `GET /presence` theo projection server; phần bạn bè (projection `friends`, RPC `ListFriendIds`, giao `/user/queue/events`) thêm ở P7 khi có Social. Không bao giờ dùng topic broadcast chung.

## 7. Kết nối lại và bù dữ liệu

- `@stomp/stompjs` tự reconnect (backoff 1 s → 30 s).
- Sau reconnect, client gọi `GET /api/v1/channels/{id}/messages?after=<id tin cuối đang có>` cho kênh đang mở để bù tin lỡ — WebSocket không đảm bảo giao, **REST là nguồn sự thật**.
- Access token sắp hết hạn: frontend refresh rồi reconnect.

## 8. Shutdown

Gateway dừng: đóng kết nối (client reconnect sang instance khác), phát `SessionDisconnected` cho từng session; nếu chết đột ngột, Presence dọn nhờ heartbeat hết hạn (90 s).
