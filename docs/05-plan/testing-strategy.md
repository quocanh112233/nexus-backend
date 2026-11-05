# Chiến lược test

File này quy định các mức test, công cụ, phạm vi và cách chạy trong CI. Mục tiêu: test tự động đủ tin cậy để mỗi phase "demo được" và đủ thuyết phục khi phỏng vấn, nhưng vẫn vừa sức một người.

## 1. Kim tự tháp

| Mức | Công cụ | Phạm vi | Chạy ở | Tỷ trọng |
|-----|---------|---------|--------|---------|
| Unit — domain | JUnit 5, AssertJ | Aggregate, VO, domain service (`PermissionCalculator`, `FriendRequest`) — không Spring, không DB |
| Unit — mapper | JUnit 5 | `XxxMapper` round-trip aggregate ↔ `XxxJpaEntity` | `mvn test` | Nhiều nhất |
| Unit — application | JUnit 5, Mockito (mock **port** và interface `XxxRepository`) | Use case: điều phối, phát event, xử lý lỗi port | `mvn test` | Nhiều |
| Slice web | `@WebMvcTest` | Validation, map lỗi Problem Details, security | `mvn test` | Vừa |
| Integration | Testcontainers (Postgres, RabbitMQ, Redis), `@DataJpaTest`/`@SpringBootTest` | `XxxDao` + `XxxRepositoryImpl` + Flyway thật (ADR-009), outbox relay, consumer + inbox, concurrency | `mvn verify` (Failsafe, `*IT`) | Vừa |
| gRPC | `grpc-testing` in-process; Testcontainers cho server thật | Adapter server/client, deadline, map status | `mvn verify` | Ít |
| Contract | `contracts/events/*.json` + `buf breaking` | Event và proto không vỡ hợp đồng | `mvn verify`, `proto.yml` | Mỗi event/RPC 1 cặp |
| Kiến trúc | ArchUnit | Luật phân lớp, `domain` không import framework/proto | `mvn test` | 1 class/service |
| Smoke hệ thống | Script bash/HTTP + `docker compose --wait` | Đăng ký → tạo server → gửi tin qua gateway | `compose-smoke.yml` | 1 kịch bản |
| E2E | Playwright | 1-2 luồng UI chính | `compose-smoke.yml` (từ P9) | Rất ít |
| Hiệu năng | k6 | NFR-001..NFR-003 | Thủ công (P9), kết quả commit | - |

## 2. Test bắt buộc cho các điểm khó (phỏng vấn hay hỏi)

| Chủ đề | Test | Phase |
|-------|------|-------|
| Concurrency invite | 20 thread join invite `maxUses=5` → đúng 5 member | P2 |
| Unique đồng thời | 2 request đăng ký cùng username → 1 thành công | P1 |
| Optimistic lock | 2 PATCH cùng `version` → 1 `409` | P3, P4 |
| Idempotency gửi tin | Cùng `nonce` 2 lần → 1 tin | P4 |
| Reaction đồng thời | 50 thread cùng reaction → 1 dòng | P4 |
| Refresh token reuse | Dùng lại token cũ → family bị thu hồi | P1 |
| gRPC deadline | Server chậm → caller lỗi < deadline + 100 ms | P4 |
| Circuit breaker | N lỗi → `OPEN`, gọi tiếp không ra mạng | P4 |
| Outbox | Rollback transaction → không có event; broker tắt → event phát sau khi bật | P5 |
| Inbox | Cùng `eventId` 2 lần → xử lý 1 lần | P5 |
| DLQ | Payload hỏng → vào `.dlq` sau retry | P5 |
| Ordering | Event đến ngược thứ tự → projection vẫn đúng | P5, P8 |
| Fan-out multi-instance | 2 instance realtime, client ở 2 instance nhận nhau | P6 (smoke thủ công + IT với 2 context) |
| Riêng tư presence (NFR-018) | Người lạ không nhận `PresenceChanged`, `GET /presence` không trả; bị ban/rời/hủy kết bạn → hết thấy | P6, P7 |
| Kick/ban thứ bậc | Mod không kick/ban được Admin, owner, chính mình | P3 |
| Ban vs join đồng thời | 50 lần song song → không còn member bị ban | P3 |

## 3. Contract test cho event

1. Producer: test serialize `MessageCreated` → so sánh **tập field và kiểu** với `contracts/events/messaging/MessageCreated.v1.json` (field thừa trong producer → fail để buộc cập nhật hợp đồng).
2. Consumer: test đọc file mẫu bằng DTO của consumer → không lỗi, map đúng giá trị.
3. Đổi hợp đồng = đổi file JSON + [event-catalog.md](../02-architecture/event-catalog.md) trong cùng PR.

Lý do không dùng Pact/Spring Cloud Contract: thêm hạ tầng (broker Pact) quá sức cho dự án một người; file mẫu + monorepo đạt 80% lợi ích (P-21).

## 4. Quy ước

- Comment test 3 dòng (kịch bản / input / output) — [coding-conventions.md](../04-project-structure/coding-conventions.md#2-quy-ước-comment-đã-chốt--d-12).
- Testcontainers dùng singleton container trong `libs/nexus-test-support` để giảm thời gian.
- Không mock domain; chỉ mock port.
- Không test getter/setter, không test framework.

## 5. Coverage & CI

| Chỉ số | Mục tiêu |
|-------|---------|
| JaCoCo line coverage `domain` + `application` | ≥ 70% (NFR-040), báo cáo trong job summary |
| Toàn service | Không đặt ngưỡng (tránh test vô nghĩa cho config) |
| Thời gian CI một service | < 8 phút |

Mọi PR phải xanh: unit + IT + ArchUnit + Spotless + (proto) buf. `compose-smoke.yml` chạy trên `main` và hằng đêm.
