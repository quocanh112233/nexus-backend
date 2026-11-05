# Yêu cầu chức năng (Functional Requirements)

File này liệt kê mọi yêu cầu chức năng theo module, có ID `FR-xxx` dùng xuyên suốt tài liệu, plan và test. Ưu tiên: **MVP** (giai đoạn đầu) hoặc **Phase 2** (sau MVP). Cột "Phase triển khai" khớp với [roadmap.md](../05-plan/roadmap.md).

Quy ước dải ID: Identity `FR-001..019`, Community `FR-020..049`, Messaging `FR-050..069`, Social/DM `FR-070..079`, Presence `FR-080..089`, Notification `FR-090..099`, Upload `FR-100..109`.

## 1. Identity (tài khoản)

| ID | Yêu cầu | Tiêu chí chấp nhận chính | Ưu tiên | Phase |
|----|---------|--------------------------|---------|-------|
| FR-001 | Đăng ký bằng email, username, password | email/username duy nhất (không phân biệt hoa thường); password ≥ 8 ký tự; phát `UserRegistered` | MVP | P1 |
| FR-002 | Đăng nhập | Trả access token (JWT 15 phút) + đặt cookie refresh token; sai thông tin trả 401 chung chung | MVP | P1 |
| FR-003 | Làm mới access token | Refresh token rotation; dùng lại token cũ → thu hồi cả family | MVP | P1 |
| FR-004 | Đăng xuất thiết bị hiện tại | Thu hồi refresh token hiện tại, xóa cookie | MVP | P1 |
| FR-005 | Đăng xuất mọi thiết bị | Thu hồi mọi refresh token của user | Phase 2 | - |
| FR-006 | Xem profile (của mình và public profile người khác) | Public profile: `id, username, displayName, avatarUrl, aboutMe` | MVP | P1 |
| FR-007 | Cập nhật profile | Sửa `displayName` (1-32), `aboutMe` (≤190); phát `UserProfileUpdated` | MVP | P1 |
| FR-008 | Đổi avatar | Qua FR-100; lưu `avatarPublicId`, `avatarUrl`; phát `UserProfileUpdated` | MVP | P1 |
| FR-009 | Đổi mật khẩu | Cần mật khẩu cũ; thu hồi refresh token khác | Phase 2 | - |
| FR-010 | Tra cứu user theo username | Trả public profile; dùng cho kết bạn | MVP | P1 |
| FR-011 | Xác minh email, quên mật khẩu | Backlog BL-10 | Phase 2 | - |

## 2. Community (server, kênh, thành viên, role, invite)

| ID | Yêu cầu | Tiêu chí chấp nhận chính | Ưu tiên | Phase |
|----|---------|--------------------------|---------|-------|
| FR-020 | Tạo server | Người tạo là owner + member; tự tạo role `@everyone` (quyền mặc định), category "Text Channels", kênh `general`; phát `ServerCreated`, `CategoryCreated`, `ChannelCreated`, `MemberJoined` | MVP | P2 |
| FR-021 | Đổi tên server | Cần `MANAGE_SERVER`; phát `ServerUpdated` | MVP | P2 |
| FR-022 | Xóa server | Chỉ owner; phát `ServerDeleted` | MVP | P2 |
| FR-023 | Danh sách server của tôi | Chỉ server đang là member | MVP | P2 |
| FR-024 | Tạo/sửa/xóa/sắp xếp category | Cần `MANAGE_CHANNELS`; xóa category → kênh con về "không category" | MVP | P2 |
| FR-025 | Tạo/sửa/xóa/sắp xếp kênh text, chuyển category | Cần `MANAGE_CHANNELS`; tên 1-100 ký tự, chữ thường, `-`; phát `ChannelCreated/Updated/Deleted` | MVP | P2 |
| FR-026 | Tạo invite link | Cần `CREATE_INVITE`; tùy chọn `maxUses` (0 = không giới hạn), `expiresIn` (mặc định 7 ngày) | MVP | P2 |
| FR-027 | Xem trước invite | Không cần là member: tên server, số member; invite hết hạn/thu hồi trả 404 | MVP | P2 |
| FR-028 | Tham gia server qua invite | Tăng `uses` nguyên tử; đã là member → trả về server (idempotent); phát `MemberJoined` | MVP | P2 |
| FR-029 | Thu hồi invite | Cần `MANAGE_SERVER` hoặc là người tạo invite | MVP | P2 |
| FR-030 | Rời server | Owner không được rời (phải xóa server); phát `MemberLeft` | MVP | P2 |
| FR-031 | Danh sách thành viên | Phân trang; kèm role id của mỗi member | MVP | P2 |
| FR-032 | Tạo/sửa/xóa/sắp xếp role | Cần `MANAGE_ROLES`; chỉ thao tác role **thấp hơn** role cao nhất của mình; không cấp quyền mình không có; phát `RoleCreated/Updated/Deleted` | MVP | P3 |
| FR-033 | Gán/gỡ role cho member | Cần `MANAGE_ROLES` + luật thứ bậc; phát `MemberRolesChanged` | MVP | P3 |
| FR-034 | Permission overwrite theo kênh | Allow/deny cho role hoặc member tại kênh; cần `MANAGE_ROLES`; phát `ChannelPermissionsChanged` | MVP | P3 |
| FR-035 | Chuyển quyền owner | | Phase 2 | - |
| FR-036 | Kick member | Cần `KICK_MEMBERS`; không kick được owner, chính mình, hoặc member có role cao nhất ≥ role cao nhất của actor (owner bỏ qua luật thứ bậc); xóa membership + role; người bị kick **join lại được** bằng invite; phát `MemberKicked` | MVP | P3 |
| FR-037 | Ban member | Cần `BAN_MEMBERS` + cùng luật thứ bậc như FR-036; tùy chọn `reason` (≤ 512 ký tự); xóa membership nếu đang là member; ghi `server_bans(banned_by, reason, created_at)`; join qua invite bị từ chối `403 BANNED_FROM_SERVER`; ban lại người đã ban → idempotent; phát `MemberBanned` | MVP | P3 |
| FR-038 | Audit log đầy đủ mọi thao tác quản trị | Phase 2 (ban đã có audit tối thiểu trong `server_bans` — P-34) | Phase 2 | - |
| FR-039 | Xem danh sách ban, gỡ ban | Cần `BAN_MEMBERS`; danh sách gồm user, người ban, lý do, thời điểm; gỡ ban phát `MemberUnbanned`, người đó join lại được bằng invite | MVP | P3 |

## 3. Messaging (tin nhắn)

Áp dụng cho cả kênh server và kênh DM (quyền trong DM: xem [permission-model.md](permission-model.md#6-quyền-trong-dm)).

| ID | Yêu cầu | Tiêu chí chấp nhận chính | Ưu tiên | Phase |
|----|---------|--------------------------|---------|-------|
| FR-050 | Gửi tin nhắn text | Cần `VIEW_CHANNEL` + `SEND_MESSAGES`; 1-2000 ký tự; client gửi `nonce` để chống gửi trùng; phát `MessageCreated` | MVP | P4 |
| FR-051 | Sửa tin nhắn của mình | Chỉ tác giả; đánh dấu `editedAt`; phát `MessageEdited` | MVP | P4 |
| FR-052 | Xóa tin nhắn | Tác giả hoặc `MANAGE_MESSAGES`; xóa mềm; phát `MessageDeleted` | MVP | P4 |
| FR-053 | Reply | `replyToId` phải cùng kênh; tin gốc bị xóa vẫn hiển thị "tin nhắn đã bị xóa" | MVP | P4 |
| FR-054 | Thêm/bỏ reaction | Emoji Unicode (P-16); cần `ADD_REACTIONS`; một user một emoji một lần; phát `ReactionAdded/Removed` | MVP | P4 |
| FR-055 | Lịch sử phân trang | Cursor `before`/`after` theo id, tối đa 100/trang; cần `READ_MESSAGE_HISTORY` | MVP | P4 |
| FR-056 | Nhận tin nhắn realtime | Tin mới/sửa/xóa/reaction tới client đang mở kênh < 1s (local) | MVP | P6 |
| FR-057 | Typing indicator | Hiện "X đang gõ" tối đa 8s sau lần gõ cuối | MVP | P6 |
| FR-058 | Đính kèm ảnh/file | Tối đa 5 file/tin; cần `ATTACH_FILES`; dùng FR-101 | MVP | P4 |
| FR-059 | @mention user | Cú pháp `<@userId>`; tối đa 20 mention/tin; người được mention nhận đếm mention (FR-091) | MVP | P4 (lưu), P8 (đếm) |

## 4. Social & DM (trong Messaging — P-05)

| ID | Yêu cầu | Tiêu chí chấp nhận chính | Ưu tiên | Phase |
|----|---------|--------------------------|---------|-------|
| FR-070 | Gửi lời mời kết bạn | Theo `userId` (frontend tra username qua FR-010); không tự mời mình; không trùng lời mời đang chờ; nếu B đã mời A → tự thành bạn; phát `FriendRequestSent` | MVP | P7 |
| FR-071 | Chấp nhận / từ chối | Chỉ người nhận; phát `FriendRequestAccepted` / `FriendRequestDeclined` | MVP | P7 |
| FR-072 | Hủy lời mời đã gửi | Chỉ người gửi, khi còn `PENDING`; phát `FriendRequestCanceled` | MVP | P7 |
| FR-073 | Xem bạn bè + lời mời đến/đi | | MVP | P7 |
| FR-074 | Hủy kết bạn | Phát `FriendRemoved`; DM cũ còn đọc được nhưng không gửi được | MVP | P7 |
| FR-075 | Mở DM 1-1 | Chỉ với bạn bè (P-15); mở lại trả DM cũ (idempotent); phát `DmChannelCreated` lần đầu | MVP | P7 |
| FR-076 | Nhắn trong DM | Dùng lại FR-050..FR-059 | MVP | P7 |
| FR-077 | Chặn người dùng | Backlog BL-06 | Phase 2 | - |
| FR-078 | Group DM | Backlog BL-05 | Phase 2 | - |

## 5. Presence

| ID | Yêu cầu | Tiêu chí chấp nhận chính | Ưu tiên | Phase |
|----|---------|--------------------------|---------|-------|
| FR-080 | Hiển thị online/offline **chỉ cho người có quan hệ** | Chỉ **bạn bè** và **người chung ≥ 1 server** thấy trạng thái (D-18); người lạ luôn thấy như không có thông tin (API không trả trạng thái); đổi trạng thái tới người được thấy ≤ 5 s khi kết nối/ngắt bình thường, ≤ 90 s khi gateway chết | MVP | P6 (người chung server), P7 (bạn bè) |
| FR-081 | Đa thiết bị | Online nếu còn ≥1 session | MVP | P6 |
| FR-082 | Idle / DND / invisible | Backlog BL-07 | Phase 2 | - |
| FR-083 | Cập nhật tập người được thấy khi quan hệ đổi | Rời/bị kick/bị ban khỏi server chung cuối cùng hoặc hủy kết bạn → không còn nhận trạng thái; kết bạn/vào server chung → nhận được | MVP | P6, P7 |

## 6. Notification (unread, mention)

| ID | Yêu cầu | Tiêu chí chấp nhận chính | Ưu tiên | Phase |
|----|---------|--------------------------|---------|-------|
| FR-090 | Đánh dấu kênh chưa đọc | Kênh in đậm khi có tin mới user chưa đọc; tin do chính mình gửi không tính | MVP | P8 |
| FR-091 | Đếm mention chưa đọc | Badge số theo kênh và tổng theo server | MVP | P8 |
| FR-092 | Đánh dấu đã đọc | Khi mở kênh/cuộn tới cuối; `lastReadMessageId` chỉ tăng | MVP | P8 |
| FR-093 | Unread cho DM | Badge trên danh sách DM | MVP | P8 |
| FR-094 | @everyone / @role | Backlog BL-08 | Phase 2 | - |
| FR-095 | Push / email notification | Backlog BL-09 | Phase 2 | - |

## 7. Upload (Cloudinary — D-06)

| ID | Yêu cầu | Tiêu chí chấp nhận chính | Ưu tiên | Phase |
|----|---------|--------------------------|---------|-------|
| FR-100 | Cấp chữ ký upload avatar (Identity) | Folder `nexus/avatars/{userId}`; chỉ ảnh `jpg,png,webp,gif`; ≤ 5 MB | MVP | P1 |
| FR-101 | Cấp chữ ký upload file đính kèm (Messaging) | Folder `nexus/attachments/{channelId}/{userId}`; cần `ATTACH_FILES`; ≤ 25 MB | MVP | P4 |
| FR-102 | Kiểm tra file khi lưu | Backend kiểm tra `publicId` thuộc folder đã cấp cho user; loại/kích thước hợp lệ | MVP | P1, P4 |
| FR-103 | Dọn file mồ côi | Backlog BL-14 | Phase 2 | - |
