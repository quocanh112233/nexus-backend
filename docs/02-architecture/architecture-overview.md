# Tổng quan kiến trúc

File này mô tả bức tranh toàn hệ thống Nexus: thành phần, cách chia service và lý do, ba kiểu giao tiếp (REST ở biên, gRPC đồng bộ, event bất đồng bộ) và khi nào dùng kiểu nào.

## 1. Thành phần

| Thành phần | Loại | Trách nhiệm | Lưu trữ | Phase ra đời |
|-----------|------|-------------|---------|--------------|
| `api-gateway` | Gateway | Điểm vào duy nhất cho REST + WebSocket; verify JWT; rate limit; gom OpenAPI | Redis (rate limit) | P2 |
| `identity-service` | Domain service | Tài khoản, xác thực, profile, chữ ký avatar; phát JWKS | schema `identity` | P1 |
| `community-service` | Domain service | Server, category, kênh, member, role, permission, invite | schema `community` | P2 |
| `messaging-service` | Domain service | Tin nhắn, reply, reaction, đính kèm, bạn bè, DM | schema `messaging`, Redis (cache quyền) | P4 |
| `realtime-gateway` | Gateway | Giữ kết nối WebSocket/STOMP, đẩy event tới client, typing | Bộ nhớ (subscription) | P6 |
| `presence-service` | Domain service | Online/offline theo session; quyết định ai được thấy (bạn bè ∪ người chung server — D-18) | Redis | P6 |
| `notification-service` | Domain service | Unread, đếm mention | schema `notification` | P8 |
| PostgreSQL | Hạ tầng | 1 instance, schema + user riêng mỗi service | | P0 |
| RabbitMQ | Hạ tầng | Message broker cho event | | P0 (dùng từ P5) |
| Redis | Hạ tầng | Presence, rate limit, cache | | P0 |
| Cloudinary | Bên ngoài | Lưu ảnh/file | | P1 |
| `grafana/otel-lgtm` | Hạ tầng | Log tập trung, metric, trace | | P2 |

Sơ đồ: [component-diagram.md](../03-diagrams/component-diagram.md).

## 2. Vì sao chia như vậy

| Ranh giới | Lý do |
|----------|-------|
| Identity tách riêng | Dữ liệu nhạy cảm (password, token); mọi service khác chỉ cần **verify** JWT bằng public key → không phụ thuộc runtime vào Identity |
| Community tách khỏi Messaging | Community: dữ liệu cấu trúc, ghi ít, quy tắc phức tạp (quyền). Messaging: ghi rất nhiều, tăng trưởng nhanh. Khác nhịp thay đổi và khác nhu cầu scale |
| Friend/DM nằm trong Messaging (P-05) | DM dùng chung mô hình tin nhắn; tách `social-service` là bài tập tùy chọn (BL-12) |
| Presence tách riêng | Dữ liệu tạm, tần suất cao, chỉ Redis; chết không ảnh hưởng gửi tin |
| Notification tách riêng | Hoàn toàn phản ứng theo event → ví dụ thuần túy của consumer + projection |
| `realtime-gateway` tách khỏi service domain | Kết nối dài hạn, scale theo số kết nối, không có logic nghiệp vụ |
| Không có Media service | Upload thẳng Cloudinary (D-06) |

## 3. Lộ trình số service (P-04)

| Mốc | Service đang chạy | Lý do dừng ở đây |
|-----|------------------|-----------------|
| P1 | identity | Một service chạy trọn vòng: domain, REST, Postgres, Flyway, Dockerfile, CI |
| P2-P3 | identity, community, api-gateway | Học ranh giới thứ hai, gateway, xác thực phân tán qua JWKS |
| P4 | + messaging | Học gRPC (Messaging → Community, Messaging → Identity) |
| P5 | (không thêm) | Học message queue trên 3 service sẵn có |
| P6 | + realtime-gateway, presence | Realtime và state tạm trong Redis |
| P8 | + notification | Consumer thuần event |

## 4. Khi nào dùng gRPC, khi nào dùng event

**Quy tắc:**
- **REST**: chỉ ở biên — frontend → API Gateway → service. Không service nào gọi REST của service khác.
- **gRPC** (đồng bộ): khi caller **cần câu trả lời ngay để quyết định tiếp** (kiểm tra quyền, kiểm tra tồn tại). Chỉ dùng cho **truy vấn**, không dùng gRPC để ra lệnh thay đổi dữ liệu service khác.
- **Event** (bất đồng bộ): khi producer **thông báo điều đã xảy ra** và không cần biết ai nghe; consumer tự cập nhật dữ liệu của mình (projection, side effect). Chấp nhận eventual consistency.
- **WebSocket**: chỉ giữa client và `realtime-gateway`.

| Luồng | Kiểu | Caller → Callee / Producer → Consumer | Vì sao |
|------|------|----------------------------------------|--------|
| Gửi tin vào kênh server | gRPC | Messaging → Community `PermissionService/CheckChannelPermissions` | Phải biết có quyền **trước** khi lưu |
| Đọc lịch sử, upload chữ ký đính kèm, xóa tin người khác | gRPC | Messaging → Community `CheckChannelPermissions` | Như trên |
| Gửi lời mời kết bạn | gRPC | Messaging → Identity `UserService/GetUser` | Cần chắc user tồn tại |
| Client subscribe kênh realtime | gRPC | realtime-gateway → Messaging `ChannelAccessService/CheckChannelAccess` | Quyết định cho/không cho subscribe |
| Client subscribe server realtime | gRPC | realtime-gateway → Community `PermissionService/CheckServerMembership` | Như trên |
| Presence nạp bù tập server / tập bạn của user | gRPC | Presence → Community `ListServerIdsForUser`, Messaging `SocialService/ListFriendIds` | Cần dữ liệu đầy đủ ngay để áp quy tắc riêng tư khi projection chưa có |
| Join server khi bị ban | Trong process | Community tự kiểm `server_bans` | Cùng service sở hữu dữ liệu → không cần RPC (P-35) |
| Tạo kênh → Messaging biết kênh thuộc server nào | Event | Community `ChannelCreated` → Messaging (`channel_refs`) | Messaging không cần trả lời ngay; tự giữ bản chiếu |
| Member tham gia server | Event | Community `MemberJoined` → Notification, Presence, realtime-gateway | Phản ứng phụ, không ảnh hưởng việc join thành công |
| Kick / ban member | Event | Community `MemberKicked`, `MemberBanned` → Messaging (xóa cache quyền), Notification, Presence, realtime-gateway (thu hồi subscription) | Lan truyền hậu quả, Community không cần biết ai nghe |
| Đổi role / overwrite | Event | Community `MemberRolesChanged`, `ChannelPermissionsChanged` → Messaging (xóa cache), realtime-gateway | Lan truyền thay đổi |
| Tin nhắn mới | Event | Messaging `MessageCreated` → realtime-gateway, Notification | Fan-out tới nhiều nơi; người gửi không chờ |
| Kết nối/ngắt WebSocket | Event | realtime-gateway `SessionConnected/Disconnected` → Presence | Presence tự tính trạng thái |
| Online/offline | Event | Presence `PresenceChanged` (kèm `serverIds`, `friendIds`) → realtime-gateway | Đẩy cho đúng bạn bè và người chung server |
| Đã đọc | Event | Notification `ReadStateUpdated` → realtime-gateway | Đồng bộ nhiều thiết bị |

**Phản ví dụ (không làm):**
- Community gọi gRPC sang Notification "hãy tạo read state" → sai: đảo chiều phụ thuộc, upstream biết về downstream. Dùng event `MemberJoined`.
- Messaging nhận `MessageCreated` của chính mình qua broker để lưu DB → sai: ghi trực tiếp trong transaction.
- Dùng event để "hỏi quyền" rồi chờ event trả lời → phức tạp vô ích; dùng gRPC.

## 5. Nguyên tắc dữ liệu

- Mỗi service sở hữu schema riêng (ADR-003). Chỉ lưu id của dữ liệu service khác.
- Ghi dữ liệu + event trong cùng transaction (outbox) → không mất event, không phát event cho dữ liệu chưa commit.
- Consumer idempotent (inbox `processed_event`).
- Id dạng UUIDv7 (P-08).

## 6. Bảo mật tổng quát

JWT RS256 do Identity ký; API Gateway và **mọi service** verify qua JWKS (`http://identity-service:8081/.well-known/jwks.json`). gRPC truyền JWT gốc của user trong metadata `authorization` + `x-request-id`. Chi tiết: [security.md](security.md).

## 7. Tài liệu liên quan

[bounded-contexts-and-context-map.md](bounded-contexts-and-context-map.md) · [services.md](services.md) · [grpc-design.md](grpc-design.md) · [messaging-deep-dive.md](messaging-deep-dive.md) · [event-catalog.md](event-catalog.md) · [data-model.md](data-model.md) · [api-conventions.md](api-conventions.md) · [realtime-design.md](realtime-design.md) · [security.md](security.md) · [observability.md](observability.md)
