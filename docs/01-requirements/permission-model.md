# Mô hình quyền (Permission Model)

File này định nghĩa permission, role, overwrite theo kênh và thuật toán tính quyền hiệu lực. Community là **nguồn sự thật duy nhất** về quyền; service khác hỏi qua gRPC `community.v1.PermissionService` (P-12).

## 1. Danh sách permission (bitset `bigint`)

| Bit | Permission | Phạm vi | Ý nghĩa | @everyone mặc định |
|-----|-----------|---------|---------|:-:|
| 0 | `ADMINISTRATOR` | Server | Có mọi quyền, bỏ qua overwrite | |
| 1 | `MANAGE_SERVER` | Server | Đổi tên server, thu hồi invite | |
| 2 | `MANAGE_ROLES` | Server + kênh | Tạo/sửa/gán role, sửa overwrite | |
| 3 | `MANAGE_CHANNELS` | Server + kênh | Tạo/sửa/xóa category, kênh | |
| 4 | `CREATE_INVITE` | Server + kênh | Tạo invite | ✔ |
| 5 | `VIEW_CHANNEL` | Kênh | Thấy kênh, đăng ký realtime | ✔ |
| 6 | `SEND_MESSAGES` | Kênh | Gửi tin | ✔ |
| 7 | `READ_MESSAGE_HISTORY` | Kênh | Đọc lịch sử | ✔ |
| 8 | `ATTACH_FILES` | Kênh | Đính kèm file | ✔ |
| 9 | `ADD_REACTIONS` | Kênh | Thêm reaction | ✔ |
| 10 | `MANAGE_MESSAGES` | Kênh | Xóa tin của người khác | |
| 11 | `KICK_MEMBERS` | Server | Kick member (FR-036) | |
| 12 | `BAN_MEMBERS` | Server | Ban, xem danh sách ban, gỡ ban (FR-037, FR-039) | |

Giá trị trong code: enum `Permission` với `long mask = 1L << bit`. Proto dùng `enum Permission` cùng tên (xem [grpc-design.md](../02-architecture/grpc-design.md)).

## 2. Role

- Mỗi server có role `@everyone` (`is_default = true`, `position = 0`), không xóa được, mọi member ngầm có.
- Role khác có `position` ≥ 1; số lớn hơn = cao hơn. Owner không cần role, luôn có mọi quyền.
- **Luật thứ bậc** (FR-032, FR-033): actor chỉ được tạo/sửa/xóa/gán role có `position` **nhỏ hơn** role cao nhất của actor; không được cấp permission mà actor không có. Owner bỏ qua luật này.
- **Luật thứ bậc cho kick/ban** (FR-036, FR-037): actor chỉ kick/ban được member có role cao nhất **thấp hơn** role cao nhất của actor; không ai kick/ban được owner hay chính mình. Cùng `RoleHierarchyPolicy`.

## 3. Permission overwrite theo kênh

Bảng `channel_permission_overwrites(channel_id, target_type, target_id, allow, deny)`; `target_type ∈ {ROLE, MEMBER}`. Chỉ áp dụng cho permission phạm vi "Kênh" ở bảng trên. Overwrite theo category: backlog BL-11.

## 4. Thuật toán tính quyền hiệu lực

```text
computeChannelPermissions(member, channel):
  1. nếu member là owner của server            → ALL
  2. base = everyone.permissions
     với mỗi role r của member: base |= r.permissions
  3. nếu base có ADMINISTRATOR                 → ALL
  4. p = base
     ow = overwrite(ROLE, @everyone):  p = (p & ~ow.deny) | ow.allow
     gộp các overwrite ROLE của member: deny = OR(deny), allow = OR(allow)
                                          p = (p & ~deny) | allow
     ow = overwrite(MEMBER, member.userId): p = (p & ~ow.deny) | ow.allow
  5. nếu p không có VIEW_CHANNEL              → 0
  6. nếu p không có SEND_MESSAGES             → bỏ ATTACH_FILES
  7. trả p
```

Hiện thực: domain service thuần `PermissionCalculator` trong `community-service/domain` — không phụ thuộc Spring, unit test dạng bảng (mỗi bước một case).

## 5. Kiểm tra quyền ở đâu

| Hành động | Service thực thi | Cách lấy quyền |
|----------|------------------|----------------|
| Quản lý server/kênh/role/invite, kick, ban, gỡ ban | Community | Tính trực tiếp trong process |
| Tham gia qua invite khi bị ban | Community | Kiểm `server_bans` trong cùng transaction — không cần RPC (P-35) |
| Gửi/sửa/xóa tin, reaction, upload chữ ký, đọc lịch sử | Messaging | gRPC `CheckChannelPermissions` + cache Redis (P-12) |
| Đăng ký topic kênh realtime | realtime-gateway | gRPC `messaging.v1.ChannelAccessService/CheckChannelAccess` (Messaging tự phân biệt kênh server/DM) |
| Đăng ký topic server realtime | realtime-gateway | gRPC `community.v1.PermissionService/CheckServerMembership` |

Cache quyền ở Messaging bị vô hiệu khi nhận `RoleUpdated`, `RoleDeleted`, `MemberRolesChanged`, `MemberLeft`, `MemberKicked`, `MemberBanned`, `ChannelPermissionsChanged`, `ServerDeleted` (xem [event-catalog.md](../02-architecture/event-catalog.md)). Cửa sổ stale tối đa = TTL 60 s nếu event đến trễ.

## 6. Quyền trong DM

Không có role. Participant của DM có `VIEW_CHANNEL, SEND_MESSAGES, READ_MESSAGE_HISTORY, ATTACH_FILES, ADD_REACTIONS`. Nếu hai người **không còn là bạn** (FR-074): mất `SEND_MESSAGES`, `ATTACH_FILES`, `ADD_REACTIONS`; vẫn đọc được. Messaging tự tính, không gọi Community.

## 7. Ví dụ

| Tình huống | Kết quả |
|-----------|---------|
| @everyone có `SEND_MESSAGES`; kênh `announcements` overwrite @everyone deny `SEND_MESSAGES`; role `Mod` allow `SEND_MESSAGES` | Member thường: chỉ đọc. Mod: gửi được |
| Member có role `Admin` (ADMINISTRATOR); kênh deny `VIEW_CHANNEL` cho @everyone | Admin vẫn thấy (bước 3) |
| Overwrite MEMBER deny `SEND_MESSAGES` cho user X, role của X allow | X không gửi được (overwrite MEMBER áp dụng sau cùng) |
| Mod (position 2, có `BAN_MEMBERS`) ban Admin (position 5) | `403 PERMISSION_DENIED` (luật thứ bậc) |
