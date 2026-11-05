# Kế hoạch frontend (tối giản)

File này là checklist frontend theo phase: trang, component, store, tích hợp API/WebSocket, tiêu chí xong.

> **Lựa chọn có chủ đích (P-30):** người dùng nhắm vị trí Backend Java, nên frontend chỉ ở mức **đủ trang để demo** từng phase. Dùng shadcn/ui có sẵn, không thiết kế giao diện riêng, không responsive mobile, không animation, không SSR phức tạp. Mỗi phase frontend chỉ làm phần cần cho demo backend của phase đó.

Cấu trúc: [frontend-structure.md](../04-project-structure/frontend-structure.md).

| Phase | Trang / component | Store | Tích hợp | Tiêu chí xong |
|-------|-------------------|-------|----------|---------------|
| P0 | Khởi tạo Next.js + Tailwind + shadcn; `Dockerfile`; workflow `frontend.yml`; `/api/health` | - | - | `docker build` chạy, container healthy, CI xanh |
| P1 | `/login`, `/register`, `/settings/profile` (form + upload avatar) | `authStore`, `usersStore` | `lib/http.ts` (refresh 1 lần khi 401), `lib/cloudinary.ts` | Đăng ký → đăng nhập → đổi avatar; F5 vẫn đăng nhập (refresh cookie) |
| P2 | Khung 3 cột `(app)/layout.tsx`; danh sách server; tạo server; danh sách category/kênh; `/invite/[code]`; tạo invite | `serversStore` | Đổi base URL sang gateway `:8080` | 2 tài khoản: tạo server, join bằng link |
| P3 | `servers/[id]/settings`: tab Role (danh sách, sửa quyền dạng checkbox), tab Member (gán role, nút Kick, nút Ban + ô lý do), tab Bans (danh sách, gỡ ban), dialog overwrite kênh | `serversStore.myPermissions` | `GET /permissions/@me` để ẩn nút | Ẩn nút khi thiếu quyền; kịch bản `announcements` |
| P4 | Trang kênh: `MessageList` (cuộn lên tải thêm), `Composer` (nonce, đính kèm), reply, reaction, sửa/xóa | `messagesStore` | Poll `?after=` mỗi 3 s (tạm) | Chat được giữa 2 tab với độ trễ ≤ 3 s; lỗi 503 hiện nút "Thử lại" |
| P5 | Không có việc frontend (phase thuần backend) | - | - | - |
| P6 | Bỏ polling; typing indicator; chấm online cho người chung server (subscribe mọi `/topic/servers/{id}` của tôi) | `presenceStore`, typing trong `messagesStore` | `lib/ws.ts` STOMP, subscribe theo kênh/server, bù tin sau reconnect | Tin hiện ngay; tắt mạng rồi bật → không thiếu tin |
| P7 | `/channels/@me`: bạn bè, lời mời, thêm bạn theo username; trang DM | `socialStore` | `/user/queue/events` | Kết bạn + DM giữa 2 tài khoản realtime |
| P8 | Kênh in đậm, badge mention ở kênh/server/DM; đánh dấu đã đọc khi xem cuối kênh | `notificationsStore` | `GET/PUT /read-states`, event `ReadStateUpdated`, `MentionCreated` | Mở 2 tab cùng user: đọc ở tab 1 → tab 2 hết in đậm |
| P9 | Sửa lỗi hiển thị; 1-2 test Playwright (đăng ký → tạo server → gửi tin) | - | Chạy e2e trong `compose-smoke.yml` | E2E xanh trong CI; GIF demo |

Test frontend tối thiểu: Vitest cho `lib/http.ts` (refresh 1 lần) và reducer xử lý event trong `messagesStore` (bỏ trùng theo nonce, bỏ edit có `version` cũ). Không viết test component.

Không làm (có chủ đích): dark mode, i18n, responsive, markdown trong tin nhắn, emoji picker tùy chỉnh (dùng danh sách emoji cố định ~20 cái), virtualized list.
