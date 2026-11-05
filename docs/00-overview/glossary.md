# Thuật ngữ (Ubiquitous Language)

File này định nghĩa ngôn ngữ chung (ubiquitous language theo DDD) dùng trong code, API, event và tài liệu. Tên trong cột "Tên trong code" là tên bắt buộc dùng.

## 1. Thuật ngữ nghiệp vụ

| Thuật ngữ | Tên trong code | Bounded context | Định nghĩa |
|----------|---------------|-----------------|-----------|
| Người dùng | `User` | Identity | Tài khoản đăng nhập, có `username` duy nhất, `displayName`, avatar |
| Phiên làm mới | `RefreshToken` | Identity | Token opaque dùng để lấy access token mới; thuộc một `family` để phát hiện reuse |
| Server | `Server` | Community | Một cộng đồng (Discord gọi là guild). Có một `owner` |
| Category | `Category` | Community | Nhóm kênh trong server, chỉ để sắp xếp |
| Kênh | `Channel` | Community | Kênh text trong server (`type = TEXT`) |
| Thành viên | `Member` | Community | Quan hệ user ↔ server; khóa `(serverId, userId)` |
| Role | `Role` | Community | Tập permission có `position` (thứ bậc) trong server |
| @everyone | `Role.isDefault = true` | Community | Role mặc định, mọi member đều có, không xóa được |
| Permission | `Permission` (enum bit) | Community | Một quyền, ví dụ `SEND_MESSAGES` |
| Permission overwrite | `PermissionOverwrite` | Community | Ghi đè allow/deny cho một role hoặc member tại một kênh |
| Lời mời vào server | `Invite` | Community | Mã `code` để tham gia server, có hạn và giới hạn lượt |
| Kick | `Member.kick(...)` | Community | Đuổi member khỏi server; được join lại |
| Ban | `ServerBan` | Community | Cấm user khỏi server (kèm người ban, lý do); không join lại được tới khi gỡ ban |
| Tin nhắn | `Message` | Messaging | Nội dung gửi vào một kênh (server channel hoặc DM channel) |
| Trả lời | `replyToId` | Messaging | Tin nhắn tham chiếu tới tin nhắn khác cùng kênh |
| Reaction | `Reaction` | Messaging | Emoji Unicode gắn vào tin nhắn bởi một user |
| Tệp đính kèm | `Attachment` | Messaging | File đã upload lên Cloudinary, lưu `publicId` + URL |
| Mention | `Mention` | Messaging, Notification | Cú pháp `<@userId>` trong nội dung tin nhắn |
| Lời mời kết bạn | `FriendRequest` | Messaging (social) | Yêu cầu kết bạn, trạng thái `PENDING/ACCEPTED/DECLINED/CANCELED` |
| Tình bạn | `Friendship` | Messaging (social) | Cặp user là bạn, lưu `(userLowId, userHighId)` |
| Kênh DM | `DmChannel` | Messaging (social) | Kênh nhắn riêng giữa 2 bạn bè |
| Tham chiếu kênh | `ChannelRef` | Messaging | Bản chiếu cục bộ: kênh thuộc server nào hoặc là DM |
| Trạng thái hiện diện | `PresenceStatus` | Presence | `ONLINE` / `OFFLINE` |
| Người được thấy presence | `PresenceAudience` | Presence | Bạn bè ∪ người chung ≥ 1 server (D-18) |
| Projection quan hệ | `RelationshipProjection` | Presence | Tập server và tập bạn của mỗi user, dựng từ event + nạp bù qua gRPC |
| Phiên kết nối | `Session` | Presence, realtime | Một kết nối WebSocket của một user trên một instance gateway |
| Trạng thái đọc | `ReadState` | Notification | Tin nhắn cuối user đã đọc trong một kênh + số mention chưa đọc |
| Chưa đọc | `unread` | Notification | Kênh có `lastMessageId > lastReadMessageId` |

## 2. Thuật ngữ kỹ thuật

| Thuật ngữ | Định nghĩa trong Nexus |
|----------|------------------------|
| Bounded context | Ranh giới mô hình nghiệp vụ; trong Nexus mỗi context ≈ một service |
| Aggregate | Cụm entity thay đổi cùng nhau trong 1 transaction, truy cập qua aggregate root |
| Domain event | Sự kiện nghiệp vụ đã xảy ra trong một aggregate (thì quá khứ, ví dụ `MessageCreated`) |
| Integration event | Domain event đã được đóng gói (envelope) để phát qua RabbitMQ |
| Projection / read model | Bảng cục bộ dựng từ event của service khác (ví dụ `channel_refs`) |
| Outbox | Bảng `outbox_event` ghi cùng transaction với dữ liệu nghiệp vụ, sau đó relay ra broker |
| Inbox | Bảng `processed_event` ghi nhận event đã xử lý để chống xử lý trùng |
| Exchange / queue / binding | Khái niệm RabbitMQ, xem [messaging-deep-dive.md](../02-architecture/messaging-deep-dive.md) |
| DLQ | Dead letter queue: nơi chứa message xử lý lỗi quá số lần retry |
| RPC | Một phương thức gRPC, ví dụ `CheckChannelPermissions` |
| Deadline | Thời điểm tối đa client gRPC chờ kết quả |
| Edge API | REST API frontend gọi qua API Gateway (`/api/v1/...`) |
| Internal API | gRPC giữa các service, không đi qua API Gateway |
