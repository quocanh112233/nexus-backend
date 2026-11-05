# Sẵn sàng cho portfolio / CV

File này là checklist để Nexus trở thành dự án portfolio thuyết phục cho vị trí **Backend Java**: chất lượng nhà tuyển dụng hay nhìn, cách deploy demo chi phí thấp, và cách trình bày trung thực trong CV. Tiêu chí tổng: [roadmap.md](roadmap.md#6-tiêu-chí-sẵn-sàng-đưa-vào-cv).

## 1. Checklist chất lượng

### README gốc `nexus-backend` (thứ người xem mở đầu tiên)

- [ ] Một câu mô tả + GIF demo 20-30 s (2 trình duyệt chat realtime).
- [ ] Sơ đồ kiến trúc (Mermaid từ [component-diagram.md](../03-diagrams/component-diagram.md)).
- [ ] Bảng "dùng gRPC ở đâu, event ở đâu, vì sao" (rút từ [architecture-overview.md](../02-architecture/architecture-overview.md#4-khi-nào-dùng-grpc-khi-nào-dùng-event)).
- [ ] **Chạy bằng một lệnh:** `cp .env.example .env && ./infra/scripts/gen-jwt-keys.sh && docker compose up -d --build` → mở `http://localhost:3000`. Có tài khoản demo seed sẵn.
- [ ] Badge CI cho từng service + coverage.
- [ ] Link: Swagger UI qua gateway, RabbitMQ UI, Grafana (với profile observability).
- [ ] Ảnh chụp một trace end-to-end trên Grafana Tempo (REST → gRPC → RabbitMQ → WebSocket).
- [ ] Bảng kết quả k6 (link `docs/perf/`).
- [ ] Mục "Thí nghiệm độ bền": tắt Community, gửi event trùng, kill consumer — kèm kết quả quan sát được.
- [ ] Link ADR và tài liệu thiết kế.

### Code và kỹ thuật

| Hạng mục | Mức cần đạt | Tham chiếu |
|---------|-------------|-----------|
| Test tự động trong CI | Unit + Testcontainers + ArchUnit + contract, xanh trên `main` | [testing-strategy.md](testing-strategy.md) |
| Coverage | ≥ 70% `domain` + `application` | NFR-040 |
| API docs | OpenAPI/Swagger qua gateway; proto có comment 1 dòng mỗi RPC | P-27 |
| ADR | 7 ADR trong `docs/00-overview/adr/` | [adr/README.md](../00-overview/adr/README.md) |
| Observability | Log tập trung (Loki), metric (Prometheus), trace (Tempo), 1 dashboard commit trong repo | [observability.md](../02-architecture/observability.md) |
| Docker | Dockerfile multi-stage non-root mỗi service, healthcheck, compose 1 lệnh | [ADR-007](../00-overview/adr/ADR-007-docker-packaging.md) |
| CI/CD | Path filter, GHCR tag `sha`/`latest`/version, Trivy | [devops-plan.md](devops-plan.md) |
| Lịch sử git | Conventional Commits, tag mỗi phase | [coding-conventions.md](../04-project-structure/coding-conventions.md#5-commit-và-branch-p-22) |
| Số liệu thật | k6 p95/throughput, EXPLAIN keyset vs OFFSET, có/không cache | `docs/perf/` |

## 2. Deploy bản demo công khai (chi phí thấp)

| Phương án | Chi phí | Ưu | Nhược |
|----------|--------|----|-------|
| **Oracle Cloud Always Free** (VM ARM Ampere, tối đa 4 OCPU / 24 GB) + Docker Compose + Caddy | 0 | RAM dư cho 7 JVM; giống hệt local | Đăng ký khó, đôi khi hết capacity; ARM → CI phải build image `linux/arm64` (buildx `platforms`) |
| **VPS nhỏ** (Hetzner, DigitalOcean, Vultr… 4 GB RAM) + Compose + Caddy | ~5-10 USD/tháng | Ổn định, đơn giản, amd64 | Có phí; 4 GB phải giới hạn heap chặt (NFR-005) |
| PaaS (Render, Railway, Fly.io) mỗi service một app | Free tier không đủ, thực tế trả phí theo số app | Không quản lý VM | 7 JVM → đắt/ngủ đông; WebSocket và mạng nội bộ gRPC cấu hình phức tạp hơn |
| Managed hạ tầng kèm VM (Neon/Supabase Postgres, Upstash Redis, CloudAMQP free) | 0 cho tầng free | Giảm tải VM | Thêm cấu hình; giới hạn free tier; khác môi trường local |

**Trạng thái:** người dùng **chưa quyết định** nơi deploy — hoãn tới đầu P9 (không ảnh hưởng thiết kế các phase trước). **Đề xuất (P-28):** Oracle Always Free nếu tạo được tài khoản, không thì VPS 4 GB. Frontend trên **Vercel Hobby** (miễn phí) hoặc chung VM. Domain miễn phí dạng `*.duckdns.org` hoặc subdomain có sẵn; Caddy tự cấp TLS (bắt buộc cho cookie `Secure` và `wss://`).

**Có cần Kubernetes không? Không.** Một VM + Docker Compose đủ cho demo, đúng với cách chạy local, chi phí 0-10 USD. K8s chỉ đáng làm như bài tập riêng ([learning-path.md](learning-path.md#mở-rộng-tùy-chọn)); ghi K8s vào CV khi chưa vận hành thật là phóng đại.

Lưu ý vận hành demo: seed dữ liệu demo, rate limit chặt hơn, Cloudinary folder riêng, giới hạn upload nhỏ, reset dữ liệu hằng tuần (cron `docker compose down -v && up`), không mở cổng Postgres/Redis/RabbitMQ ra Internet.

## 3. Trình bày trong CV

**Nguyên tắc:** chỉ ghi điều dự án **thật sự** làm và đo được; con số lấy từ `docs/perf/` (để trống `<...>` cho tới khi đo); không gọi là "production", "hàng nghìn người dùng" hay "high-scale" nếu không có thật.

Mẫu (chọn 4-6 gạch, điền số thật):

- Thiết kế và xây dựng hệ thống chat realtime kiểu Discord theo **kiến trúc microservice** (5 service + API Gateway + WebSocket gateway) với **Java 21 / Spring Boot**, áp dụng **DDD** (bounded context, aggregate, domain event) và kiến trúc hexagonal được kiểm tra tự động bằng ArchUnit.
- Dùng **gRPC** (Protocol Buffers, kiểm tra breaking change bằng buf) cho truy vấn đồng bộ giữa service, có **deadline, retry, circuit breaker (Resilience4j)** và cache Redis; khi service quyền ngừng hoạt động, API trả lỗi có kiểm soát trong < `<x>` ms thay vì treo.
- Xây dựng giao tiếp bất đồng bộ bằng **RabbitMQ** với **transactional outbox**, consumer **idempotent** (inbox), retry + **dead-letter queue**; kiểm chứng bằng test Testcontainers cho các tình huống event trùng, consumer chết giữa chừng, broker tạm dừng.
- Realtime bằng **WebSocket/STOMP** scale ngang nhiều instance (fan-out qua queue riêng mỗi instance); presence online/offline trên **Redis** (Lua script nguyên tử, heartbeat phát hiện instance chết) với **quy tắc riêng tư** chỉ bạn bè và người chung server thấy, dựa trên projection dựng từ event + nạp bù qua gRPC.
- Phân quyền theo role kiểu Discord (bitset, overwrite theo kênh, luật thứ bậc) và **kick/ban** có kiểm soát race condition giữa ban và join (advisory lock), kiểm chứng bằng test đồng thời.
- Tối ưu PostgreSQL: **keyset pagination** + index phù hợp giảm thời gian tải lịch sử từ `<a>` ms (OFFSET) xuống `<b>` ms trên 1 triệu tin; xử lý concurrency bằng câu lệnh nguyên tử, unique constraint và optimistic locking.
- **Load test bằng k6**: gửi tin p95 `<c>` ms ở `<n>` người dùng ảo, độ trễ end-to-end tới client khác p95 `<d>` ms (máy `<cấu hình>`).
- **Docker** multi-stage non-root cho mọi service, chạy cả hệ thống bằng một lệnh Docker Compose; **CI/CD GitHub Actions** theo từng service (path filter, Testcontainers, quét Trivy, push GHCR).
- **Observability**: log tập trung, metric và **distributed tracing OpenTelemetry** xuyên REST → gRPC → RabbitMQ → WebSocket trên Grafana (Loki, Tempo, Prometheus).

Câu hỏi phỏng vấn nên chuẩn bị (trả lời bằng chính dự án): vì sao gRPC chỗ này mà event chỗ kia; làm sao không mất event; xử lý event trùng thế nào; thứ tự event; vì sao cache quyền phải ở Redis; điều gì xảy ra khi Community chết; vì sao không dùng Kafka; vì sao không K8s; trade-off của schema-per-service trên một instance.

Tư liệu: ghi `docs/journal.md` sau mỗi phase (đã học gì, lỗi khó nhất, số liệu) — nguồn cho câu chuyện phỏng vấn.
