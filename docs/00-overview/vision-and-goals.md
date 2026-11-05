# Tầm nhìn, mục tiêu học tập, Goals / Non-goals

File này nói Nexus là gì, học được gì qua nó, và phạm vi làm / không làm.

## 1. Tầm nhìn

Nexus là một web chat kiểu Discord: người dùng tạo **server** (cộng đồng), chia **kênh text** theo **category**, chat realtime, quản lý **role/permission**, kết bạn và nhắn **DM**. Sản phẩm là phương tiện; mục đích thật là **học kiến trúc microservice + DDD** trên một bài toán đủ thật.

## 2. Mục tiêu học tập (xếp theo ưu tiên)

| # | Chủ đề | Học qua đâu trong Nexus | Tài liệu chính |
|---|--------|------------------------|----------------|
| L1 | **Kiến trúc microservice**: ranh giới service, database-per-service, gateway, service discovery, resilience, observability | 5 service + 2 gateway, schema riêng, Docker Compose | [architecture-overview.md](../02-architecture/architecture-overview.md) |
| L2 | **Service giao tiếp với nhau ra sao**: gRPC đồng bộ vs event bất đồng bộ, khi nào dùng cái nào | Messaging → Community (gRPC check quyền), Community → Notification (event) | [grpc-design.md](../02-architecture/grpc-design.md), [architecture-overview.md](../02-architecture/architecture-overview.md#4-khi-nào-dùng-grpc-khi-nào-dùng-event) |
| L3 | **Message queue hoạt động thế nào**: exchange/queue/binding, ack, retry, DLQ, ordering, idempotency, outbox | RabbitMQ, outbox/inbox ở mọi service | [messaging-deep-dive.md](../02-architecture/messaging-deep-dive.md) |
| L4 | DDD chiến lược: bounded context, context map, ubiquitous language | 5 bounded context | [bounded-contexts-and-context-map.md](../02-architecture/bounded-contexts-and-context-map.md) |
| L5 | DDD chiến thuật: aggregate, entity, value object, domain event, repository | Package `domain/` mỗi service | [services.md](../02-architecture/services.md) |
| L6 | Realtime: WebSocket/STOMP, scale nhiều instance | `realtime-gateway` | [realtime-design.md](../02-architecture/realtime-design.md) |
| L7 | CI/CD theo service, container | GitHub Actions + path filter | [devops-plan.md](../05-plan/devops-plan.md) |

Lộ trình học chi tiết: [learning-path.md](../05-plan/learning-path.md).

## 3. Goals (MVP)

| Module | Nội dung | Yêu cầu |
|--------|----------|---------|
| Tài khoản | Đăng ký, đăng nhập JWT + refresh token, profile, avatar | FR-001..FR-010 |
| Server & kênh | Tạo server, category, kênh text, invite link | FR-020..FR-031 |
| Role & permission | Role theo server, override theo kênh | FR-032..FR-034 |
| Kick / ban | Kick, ban (kèm lý do), gỡ ban, chặn join lại | FR-036, FR-037, FR-039 |
| Chat realtime | Gửi/sửa/xóa, reply, reaction, typing, lịch sử phân trang, @mention | FR-050..FR-059 |
| Bạn bè & DM | Lời mời kết bạn, DM 1-1 | FR-070..FR-076 |
| Presence | Online/offline, chỉ bạn bè và người chung server thấy | FR-080, FR-081, FR-083 |
| Upload | Ảnh/file qua Cloudinary signed upload | FR-100..FR-102 |
| Unread & mention | Kênh chưa đọc, đếm mention | FR-090..FR-093 |

Danh sách đầy đủ: [functional-requirements.md](../01-requirements/functional-requirements.md).

## 4. Non-goals (ngoài phạm vi giai đoạn đầu)

| Hạng mục | Trạng thái |
|---------|-----------|
| Voice / video | Ngoài phạm vi |
| Bot / webhook | Ngoài phạm vi |
| Tìm kiếm tin nhắn | Ngoài phạm vi |
| Thread | Ngoài phạm vi |
| Audit log đầy đủ, chuyển owner, timeout/mute | Phase 2 (FR-035, FR-038) — kick/ban đã vào MVP (D-19) |
| Kubernetes, deploy cloud | Ngoài phạm vi (chỉ build image + push GHCR) |
| Media service riêng | Không làm (D-06) |

## 5. Backlog đề xuất (không thuộc MVP, chờ người dùng duyệt)

| Mã | Ý tưởng | Ghi chú |
|----|---------|---------|
| BL-01 | Nickname theo server | Nhỏ, hữu ích |
| BL-02 | Icon server (Cloudinary) | Tái dùng cơ chế chữ ký |
| BL-03 | Pin tin nhắn | |
| BL-04 | Custom emoji | Liên quan P-16 |
| BL-05 | Group DM | |
| BL-06 | Chặn người dùng (FR-077 Phase 2) | |
| BL-07 | Trạng thái idle/DND/invisible (FR-082 Phase 2) | |
| BL-08 | @everyone / @role (FR-094 Phase 2) | |
| BL-09 | Push / email notification (FR-095) | |
| BL-10 | Xác minh email, quên mật khẩu (FR-011) | Cần dịch vụ gửi mail |
| BL-11 | Permission theo category, đồng bộ xuống kênh | |
| BL-12 | Tách `social-service` khỏi Messaging | P-05 |
| BL-13 | mTLS giữa service | Bảo mật nội bộ |
| BL-14 | Dọn file mồ côi trên Cloudinary (FR-103) | |

## 6. Trạng thái

Giai đoạn: **thiết kế**. Chưa có code. Xem [roadmap.md](../05-plan/roadmap.md).
