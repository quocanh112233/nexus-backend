# Yêu cầu phi chức năng (Non-functional Requirements)

File này đặt mục tiêu đo được về hiệu năng, bảo mật, khả năng mở rộng, độ tin cậy và quan sát. Con số là mục tiêu cho môi trường local/VM demo, không phải production lớn. ID `NFR-xxx` dùng trong plan và test.

## 1. Hiệu năng

| ID | Yêu cầu | Cách đo | Phase |
|----|---------|---------|-------|
| NFR-001 | `POST /api/v1/channels/{id}/messages` p95 < 150 ms ở 50 user đồng thời | k6, báo cáo trong `docs/perf/` | P9 |
| NFR-002 | Tin nhắn tới client khác (end-to-end, qua outbox + RabbitMQ + WebSocket) p95 < 1 s | k6 WebSocket + timestamp trong payload | P9 |
| NFR-003 | Lịch sử 50 tin/trang p95 < 50 ms ở kênh 1 triệu tin (keyset pagination, dùng index) | `EXPLAIN ANALYZE` + k6 | P9 |
| NFR-004 | gRPC `CheckChannelPermissions` p95 < 20 ms (cache miss), < 2 ms (cache hit Redis) | Micrometer metric | P4 |
| NFR-005 | Mỗi service JVM chạy được với `-Xmx` ≤ 384 MB; toàn hệ thống ≤ 6 GB RAM | `docker stats` | P9 |

## 2. Bảo mật

| ID | Yêu cầu | Phase |
|----|---------|-------|
| NFR-010 | Password hash BCrypt (cost 12) | P1 |
| NFR-011 | Access token JWT RS256 15 phút; refresh token opaque, lưu SHA-256, rotation + reuse detection, cookie `HttpOnly; Secure; SameSite=Strict` | P1 |
| NFR-012 | Mọi input ở trust boundary (REST, STOMP, event, gRPC) được validate; lỗi validate trả 400/`INVALID_ARGUMENT` | P1+ |
| NFR-013 | Rate limit ở API Gateway (Redis token bucket): auth 10 req/phút/IP, API chung 100 req/phút/user, gửi tin 5 tin/5 s/user | P2, P9 |
| NFR-014 | Cloudinary API secret chỉ ở backend; chữ ký hết hạn sau 1 giờ (Cloudinary quy định); `publicId` được xác minh | P1, P4 |
| NFR-015 | gRPC và cổng nội bộ không publish ra ngoài network Docker | P4 |
| NFR-016 | Không log password, token, chữ ký | P1+ |
| NFR-018 | Riêng tư presence (D-18): không API, topic hay event giao tới client nào để lộ trạng thái online cho người không phải bạn bè và không chung server; có IT kiểm chứng | P6, P7 |
| NFR-017 | Quét lỗ hổng image (Trivy) và dependency (Dependabot) trong CI | P0, P9 |

## 3. Khả năng mở rộng & độ tin cậy

| ID | Yêu cầu | Phase |
|----|---------|-------|
| NFR-020 | Mọi service stateless (trừ state trong Redis/Postgres) → chạy được ≥ 2 instance bằng `docker compose up --scale` | P6 |
| NFR-021 | `realtime-gateway` chạy ≥ 2 instance, client ở hai instance vẫn nhận tin của nhau | P6 |
| NFR-022 | Event giao **at-least-once**; consumer **idempotent** (inbox); không mất event khi broker tạm dừng (outbox) | P5 |
| NFR-023 | Mọi gRPC call có deadline; RPC đọc retry tối đa 2 lần với `UNAVAILABLE`; circuit breaker mở sau 50% lỗi trong 20 call | P4 |
| NFR-024 | Callee gRPC chết → caller trả lỗi rõ ràng (503 `DEPENDENCY_UNAVAILABLE`) trong < 1 s, không treo | P4 |
| NFR-025 | Message lỗi sau 3 lần retry vào DLQ, có cách replay thủ công | P5 |
| NFR-026 | Graceful shutdown: service dừng nhận việc mới, xử lý xong message đang chạy (≤ 20 s) | P5 |
| NFR-027 | Healthcheck `liveness` / `readiness` cho mọi container; compose dùng `depends_on: condition: service_healthy` | P0+ |

## 4. Quan sát (Observability)

| ID | Yêu cầu | Phase |
|----|---------|-------|
| NFR-030 | Log JSON có `traceId`, `spanId`, `service`, `userId` (nếu có); gom tập trung ở Loki | P1 (JSON), P2 (Loki) |
| NFR-031 | Metric RED (rate, errors, duration) cho REST, gRPC, consumer RabbitMQ; độ dài queue; xem trên Grafana | P2, P5 |
| NFR-032 | Distributed trace xuyên REST → gRPC → RabbitMQ → consumer → WebSocket | P4, P5 |
| NFR-033 | Một dashboard Grafana "Nexus overview" commit trong repo | P9 |

## 5. Chất lượng mã & vận hành

| ID | Yêu cầu | Phase |
|----|---------|-------|
| NFR-040 | Coverage dòng ≥ 70% cho `domain` + `application` (JaCoCo), báo cáo trong CI | P1+ |
| NFR-041 | ArchUnit cưỡng chế luật phân lớp DDD ở mọi service | P1+ |
| NFR-042 | Toàn hệ thống chạy bằng một lệnh `docker compose up -d --build` | P0+ |
| NFR-043 | Mỗi service có OpenAPI (REST), proto được `buf lint` | P1, P4 |
| NFR-044 | Mọi container chạy non-root, có healthcheck | P0+ |
