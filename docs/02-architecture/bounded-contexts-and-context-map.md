# Bounded context và Context map

File này là phần DDD chiến lược: các bounded context, ngôn ngữ riêng của từng context, và quan hệ upstream/downstream giữa chúng. Sơ đồ: [context-map.md](../03-diagrams/context-map.md).

## 1. Bounded context

| Context | Service | Loại subdomain | Mô hình chính | Ghi chú ngôn ngữ |
|---------|---------|----------------|---------------|------------------|
| Identity | identity-service | Generic | `User`, `RefreshToken` | "User" ở đây có password, email; ở context khác chỉ là `userId` |
| Community | community-service | **Core** | `Server`, `Channel`, `Category`, `Member`, `Role`, `PermissionOverwrite`, `Invite` | "Member" = user *trong một server* |
| Messaging | messaging-service | **Core** | `Message`, `Reaction`, `Attachment`, `ChannelRef`; module social: `FriendRequest`, `Friendship`, `DmChannel` | "Channel" chỉ là `ChannelRef` (id + loại + serverId), không có tên/vị trí |
| Presence | presence-service | Supporting | `UserPresence`, `Session`, `RelationshipProjection` | "Quan hệ" chỉ là 2 tập id (server, bạn), không biết role hay tin nhắn |
| Notification | notification-service | Supporting | `ReadState`, `ChannelActivity`, `Mention` | "Channel" chỉ là `ChannelActivity` (tin cuối) |

Cùng một khái niệm "channel" có **ba mô hình khác nhau** ở ba context — đúng tinh thần bounded context: mỗi context chỉ giữ những gì nó cần.

## 2. Quan hệ (context map)

| Upstream | Downstream | Kiểu quan hệ DDD | Kênh | Ghi chú |
|----------|-----------|------------------|------|---------|
| Identity | Mọi context | **Open Host Service / Published Language** | JWT (JWKS), gRPC `identity.v1.UserService`, event `identity.events` | Downstream chỉ dùng `userId` + claim |
| Community | Messaging | **Customer-Supplier** + Published Language | gRPC `community.v1.PermissionService`, event `community.events` | Messaging là customer: cần API quyền ổn định |
| Community | Notification | Published Language | event `community.events` | |
| Community | realtime-gateway | Published Language | gRPC `CheckServerMembership`, event | |
| Messaging | Notification | Published Language | event `messaging.events` | |
| Messaging | realtime-gateway | Published Language | gRPC `messaging.v1.ChannelAccessService`, event | |
| realtime-gateway | Presence | Published Language | event `realtime.events` | |
| Community | Presence | Published Language | gRPC `ListServerIdsForUser`, event `community.events` | Presence giữ projection tập server để áp quy tắc riêng tư (D-18) |
| Messaging | Presence | Published Language | gRPC `messaging.v1.SocialService`, event `messaging.events` | Projection tập bạn bè |
| Presence | realtime-gateway | Published Language | event `presence.events` | Hai chiều nhưng qua event, không vòng gọi đồng bộ |
| Notification | realtime-gateway | Published Language | event `notification.events` | |
| Cloudinary | Identity, Messaging | **Anti-corruption layer** | Cloudinary SDK | Bọc trong `infrastructure/cloudinary/CloudinarySigner` + port `UploadSigner` ở `application`; domain chỉ thấy value object `MediaRef(publicId, url)` |

**Published Language** trong Nexus = file `.proto` (đồng bộ) + envelope + payload JSON đã mô tả trong [event-catalog.md](event-catalog.md) (bất đồng bộ).

## 3. Anti-corruption layer phía consumer

Mỗi consumer **tự định nghĩa DTO** cho event nó nghe (tolerant reader: bỏ qua field lạ) và **dịch** sang mô hình của mình trong `interfaces/messaging/` — không dùng chung class event giữa service. Ví dụ: Messaging nhận `ChannelCreated` (có `name`, `position`, `categoryId`) nhưng chỉ map thành `ChannelRef(channelId, serverId, kind=SERVER)`.

Với gRPC, class sinh từ proto chỉ xuất hiện ở `infrastructure/grpc/` và được dịch sang kiểu domain (ví dụ `PermissionSet`) trước khi vào `application`.

## 4. Luật phụ thuộc

- Không vòng phụ thuộc **đồng bộ**: đồ thị gRPC là DAG: `realtime-gateway → messaging → community`, `messaging → identity`, `realtime-gateway → community`, `presence → community`, `presence → messaging`.
- Upstream không bao giờ biết downstream (không gọi, không import).
- Cấm chia sẻ bảng hay class domain giữa context.
