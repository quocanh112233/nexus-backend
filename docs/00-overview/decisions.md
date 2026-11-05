# Danh sách quyết định (Decision Log)

File này liệt kê mọi quyết định kiến trúc của Nexus, tách rõ cái **đã chốt với người dùng** và cái **do kiến trúc sư đề xuất**. Các tài liệu khác tham chiếu tới mã quyết định (`D-xx`, `P-xx`) ở đây.

> Quy ước: `P-xx` là quyết định do kiến trúc sư đề xuất. Mục có nhãn **"Đã chốt — người dùng duyệt"** đã được chấp thuận; mục không có nhãn vẫn là **"Quyết định đề xuất - chờ người dùng duyệt"**. Khi duyệt, thêm nhãn và giữ nguyên mã.

## 1. Quyết định đã chốt (D-xx)

| Mã | Quyết định | Ghi chú |
|----|-----------|---------|
| D-01 | Mục tiêu chính là **học kiến trúc microservice + DDD**; số tính năng giữ gọn | Trọng tâm học: (1) kiến trúc microservice, (2) cách service giao tiếp, (3) message queue |
| D-02 | Backend: Java Spring Boot | |
| D-03 | Frontend: Next.js + Zustand | |
| D-04 | Database: PostgreSQL, **một instance**, mỗi service **một schema riêng + DB user riêng** chỉ có quyền trên schema của mình | Cấm query/foreign key chéo schema; chỉ lưu id tham chiếu |
| D-05 | Mỗi service tự chạy migration bằng **Flyway** | |
| D-06 | Upload: frontend upload thẳng lên **Cloudinary bằng signed upload**; backend chỉ cấp chữ ký; DB chỉ lưu `public_id`/URL | **Không có Media service**. Endpoint cấp chữ ký: Identity (avatar), Messaging (file đính kèm) |
| D-07 | Service: Identity, Community, Messaging, Presence (Redis), Notification + API Gateway + WebSocket gateway (`realtime-gateway`) | Được phép bắt đầu ít service rồi tách dần (xem P-04) |
| D-08 | Giao tiếp nội bộ: **gRPC cho đồng bộ** (request/response), **message broker cho bất đồng bộ** (event). **REST chỉ ở biên** (frontend → API Gateway → service), **WebSocket** cho realtime | Có idempotency, xử lý event trùng, eventual consistency, outbox |
| D-09 | CI/CD: GitHub Actions | |
| D-10 | Chạy local bằng Docker Compose (Postgres, Redis, broker, các service). Chưa dùng Kubernetes | |
| D-11 | Phạm vi MVP và non-goals như [vision-and-goals.md](vision-and-goals.md) | Voice/video, bot/webhook, search, thread: ngoài phạm vi. Kick/ban đã được đưa vào MVP (D-19) |
| D-12 | Quy ước comment code (xem [coding-conventions.md](../04-project-structure/coding-conventions.md)) | |
| D-13 | Hai repo đã tồn tại: `nexus-backend` và `nexus-frontend` (GitHub `quocanh112233`) | Phát hiện khi khảo sát thư mục làm việc; P-02 dựa trên thực tế này |

## 2. Quyết định đề xuất (P-xx)

Đã duyệt: P-01, P-02, P-03, P-04, P-10, P-12, P-14, P-26, P-33, P-34, P-36. Các mục còn lại vẫn **chờ duyệt**: P-05, P-06, P-07, P-08, P-09, P-11, P-13, P-15..P-25, P-27..P-32, P-35.

| Mã | Đề xuất | Lý do ngắn | Phương án thay thế |
|----|---------|-----------|--------------------|
| P-01 | **Đã chốt — người dùng duyệt.** **Broker: RabbitMQ** (quorum queue, topic exchange) | Một container, có Management UI trực quan để *nhìn* exchange/queue/ack/DLQ; Spring AMQP đơn giản; mô hình routing dạy được nhiều khái niệm queue cổ điển. Xem so sánh ở [messaging-deep-dive.md](../02-architecture/messaging-deep-dive.md#9-so-sánh-kafka-vs-rabbitmq) | Kafka (KRaft, 1 node): học log/partition/consumer group/replay; nặng hơn và phức tạp hơn cho người mới |
| P-02 | **Đã chốt — người dùng duyệt.** **Repo: 2 repo** — `nexus-backend` là monorepo cho mọi service backend + proto + infra; `nexus-frontend` riêng | Khớp với 2 repo đã có (D-13); backend chung repo giúp đổi proto/event đồng bộ trong 1 PR | Monorepo toàn bộ (gộp frontend); multi-repo mỗi service 1 repo (quá nặng cho người học) |
| P-03 | **Đã chốt — người dùng duyệt.** **Build: Maven multi-module** | Phổ biến, cấu hình khai báo dễ đọc, plugin protobuf ổn định, cache `~/.m2` trong CI đơn giản | Gradle (nhanh hơn, build cache tốt hơn, DSL khó hơn với người mới) |
| P-04 | **Đã chốt — người dùng duyệt.** **Bắt đầu 1 service (Identity) chạy trọn vòng**, rồi Community + API Gateway, rồi Messaging (đủ 3 service), sau đó thêm `realtime-gateway` + Presence (P6), cuối cùng Notification (P8) | Học từng bước: 1 service → ranh giới → gRPC → broker → realtime. Lộ trình ở [roadmap.md](../05-plan/roadmap.md) | Dựng đủ 5 service ngay (dễ ngợp) |
| P-05 | Friend + DM nằm trong **Messaging** (module `social`), có thể tách `social-service` sau | Theo phân chia dự kiến; DM dùng chung mô hình tin nhắn | Tách Social service ngay |
| P-06 | **Typing indicator** đi trực tiếp qua `realtime-gateway` + RabbitMQ (tin nhắn non-persistent), **không** qua Presence service | Typing là dữ liệu tạm, không có logic nghiệp vụ; thêm một hop làm tăng độ trễ. Presence chỉ giữ online/offline trong Redis | Presence nhận typing và lưu Redis TTL |
| P-07 | Java 21 LTS, Spring Boot 4.1.x (Spring Cloud bản tương thích, xác nhận ở P2) | 3.4 hết hỗ trợ 31/12/2025, 3.5 hết 30/6/2026; 4.1 hỗ trợ đến 31/7/2027. Người dùng chọn 2026-10-24 | Đã chốt |
| P-08 | Mọi id là **UUIDv7** (sắp xếp theo thời gian) | Phân trang cursor theo id, không lộ số lượng | Snowflake id (cần cấp worker id) |
| P-09 | JWT **RS256**, access token 15 phút; refresh token **opaque** 30 ngày, lưu hash, **rotation + phát hiện reuse**, gửi qua cookie HttpOnly | Service tự verify bằng JWKS, không cần gọi Identity | HS256 chia sẻ secret (kém an toàn hơn) |
| P-10 | **Đã chốt — người dùng duyệt.** Thư viện gRPC: **Spring gRPC** (`org.springframework.grpc`), sinh code bằng `io.github.ascopes:protobuf-maven-plugin`, lint/breaking check bằng **buf** | Dự án chính thức của Spring; plugin Maven được bảo trì tích cực | `net.devh grpc-spring-boot-starter` |
| P-11 | Proto đặt ở `nexus-backend/proto/` (module Maven `nexus-proto`), package `nexus.<context>.v1` | Một nguồn sự thật cho hợp đồng đồng bộ | Mỗi service tự giữ bản copy proto |
| P-12 | **Đã chốt — người dùng duyệt.** Kiểm tra permission khi gửi tin: Messaging gọi **gRPC `community.v1.PermissionService/CheckChannelPermissions`**, kết quả cache trong **Redis** (TTL 60s), xóa cache khi nhận event đổi quyền | Logic quyền phức tạp, để một chỗ (Community). Cache phải dùng chung (Redis) vì event invalidation chỉ tới **một** instance trong nhóm competing consumer | Cache cục bộ Caffeine (sai khi scale >1 instance); nhân bản toàn bộ mô hình quyền qua event |
| P-13 | Realtime: **STOMP over WebSocket**, simple broker trong bộ nhớ mỗi instance; fan-out giữa instance bằng **queue riêng mỗi instance** bind vào exchange của RabbitMQ | Dễ hiểu, scale ngang được | STOMP broker relay sang RabbitMQ STOMP plugin |
| P-14 | **Đã chốt — người dùng duyệt.** *(Thay thế — trước đây là "broadcast presence cho mọi người", đã bị người dùng bác bỏ, xem D-18.)* Diễn giải chi tiết quy tắc riêng tư presence: tập được thấy = bạn bè (từ Messaging) ∪ người chung ≥1 server (từ Community). Presence giữ projection quan hệ trong Redis dựng từ event, nạp bù lười bằng gRPC; `PresenceChanged` mang `serverIds[]` + `friendIds[]`, realtime-gateway giao vào `/topic/servers/{serverId}` (chỉ member subscribe được) và `/user/queue/events` của từng bạn; `GET /presence` lọc theo cùng quy tắc. Xem [realtime-design.md](../02-architecture/realtime-design.md#6-presence-và-quyền-riêng-tư) | Chi phí O(số server + số bạn) mỗi lần đổi trạng thái, không phải O(số member) | Gửi từng user trong mọi server (O(tổng member), đắt); gọi gRPC mỗi lần đổi trạng thái (coupling runtime) |
| P-15 | DM chỉ giữa **bạn bè** | Giảm spam, đơn giản hóa quyền | DM với người chung server |
| P-16 | Reaction chỉ **emoji Unicode** | Custom emoji cần upload + quản lý | |
| P-17 | API Gateway: **Spring Cloud Gateway** | Cùng hệ sinh thái Spring, proxy được WebSocket | Nginx/Traefik |
| P-18 | Frontend: TypeScript, Tailwind CSS + shadcn/ui, `@stomp/stompjs`, zod; test Vitest + Playwright | Phổ biến, ít cấu hình | |
| P-19 | Image Docker bằng **Dockerfile multi-stage + Spring Boot layered jar**; push lên GHCR | Học được Docker thật | Jib (không cần Dockerfile) |
| P-20 | Observability: mọi service gửi **log + metric + trace qua OTLP** (OpenTelemetry) tới một container **`grafana/otel-lgtm`** (Grafana + Loki + Tempo + Prometheus + OTel Collector); log JSON vẫn ra stdout; chạy bằng compose profile `observability` | Một container cho cả log tập trung, metric, trace: vừa sức làm một mình, demo được khi phỏng vấn | Prometheus + Jaeger + Loki riêng lẻ; ELK |
| P-21 | Test: JUnit 5 + Testcontainers + **ArchUnit** (giữ luật phân lớp DDD) + contract fixture cho event (JSON mẫu) và proto (`buf breaking`) | Nhẹ hơn Pact/Spring Cloud Contract | Pact |
| P-22 | Conventional Commits, trunk-based với nhánh ngắn hạn `feat/...`, `fix/...` | | Git flow |
| P-23 | Service discovery: **DNS của Docker Compose** (tên service cố định); gRPC client dùng `dns:///` + `round_robin` khi scale | Không cần Eureka/Consul khi chưa có K8s; Eureka để làm thí nghiệm | Eureka / Consul |
| P-24 | Resilience: **Resilience4j** (circuit breaker, retry) + gRPC deadline | Chuẩn de-facto trong Spring | |
| P-25 | Projection cục bộ: Messaging giữ `channel_refs`; Notification giữ `channel_activity`, `server_members` (dựng từ event) | Minh họa read model + eventual consistency | Gọi gRPC mỗi lần cần |
| P-26 | **Đã chốt — người dùng duyệt.** Tài liệu nằm trong `nexus-backend/docs/` (đã chuyển từ thư mục làm việc `nexus/docs`) | Gắn tài liệu với hợp đồng proto/event; nhà tuyển dụng thấy ngay trong repo | Repo `nexus-docs` riêng |
| P-27 | API docs: **springdoc-openapi** ở từng service, API Gateway gom thành một Swagger UI | Điểm cộng portfolio, gần như miễn phí | Viết tay OpenAPI |
| P-28 | *(Nơi deploy người dùng chưa quyết — Q11, hoãn tới P9, không ảnh hưởng thiết kế trước đó.)* Demo công khai: **1 VM** (Oracle Cloud Always Free hoặc VPS ~5-10 USD/tháng) chạy Docker Compose + Caddy (TLS); frontend trên Vercel free. **Không cần Kubernetes** | Rẻ, đúng với cách chạy local | Render/Fly.io (free tier yếu với 7 JVM) |
| P-29 | Redis dùng cho 3 việc: trạng thái Presence, rate limit ở API Gateway (token bucket), cache kết quả permission ở Messaging | Một hạ tầng, ba bài học (state tạm, rate limit, cache-aside) | |
| P-30 | Định hướng **backend-first**: frontend tối giản, đủ trang để demo, không đầu tư giao diện | Người dùng nhắm vị trí Backend Java | Frontend đầy đủ |
| P-31 | Load test bằng **k6**, kết quả (p95, throughput) ghi vào `docs/perf/` | Có số liệu thật cho CV | Gatling |
| P-32 | Chi tiết Docker: compose gốc dùng `include` gộp `compose.infra.yaml` + `compose.app.yaml`, profile `observability`; image tag `sha-<7>`/`latest`/`vX.Y.Z` trên GHCR; quét **Trivy** (fail khi CRITICAL có bản vá) | Một lệnh chạy được, CI an toàn cơ bản | Một file compose duy nhất |
| P-33 | **Đã chốt — người dùng duyệt.** **Không dùng Lombok**: Java `record` cho value object, DTO, payload event; entity/aggregate viết constructor + getter tường minh ([ADR-008](adr/ADR-008-no-lombok.md)) | Code tường minh dễ học, aggregate không có setter công khai (đúng DDD) | Dùng Lombok `@Getter`, `@RequiredArgsConstructor` |
| P-34 | **Đã chốt — người dùng duyệt.** Ban không cần bảng audit log riêng: bảng `server_bans` lưu `banned_by`, `reason`, `created_at` là đủ để xem ai ban ai, vì sao (audit tối thiểu); FR-038 audit log đầy đủ để Phase 2 | Giữ phạm vi gọn | Làm audit log ngay |
| P-35 | Kiểm tra ban khi join **không cần RPC**: invite và ban cùng nằm trong Community, kiểm tra `server_bans` trong cùng transaction với join | Đúng ranh giới: dữ liệu ở đâu thì kiểm ở đó | gRPC `CheckBan` (thừa) |
| P-36 | **Đã chốt — người dùng duyệt.** Xác thực gRPC không có user context (presence → community/messaging): header `x-service-token` (secret riêng mỗi service caller, qua env) + allowlist RPC theo caller ở server interceptor | Đơn giản, đủ cho mạng nội bộ Docker; mTLS để sau (BL-13) | Client-credentials OAuth2; mTLS |

## 3. Ràng buộc bổ sung từ người dùng (đã chốt)

| Mã | Ràng buộc |
|----|----------|
| D-14 | **Bắt buộc đóng gói Docker**: mỗi service + frontend có Dockerfile riêng; cả hệ thống chạy bằng Docker Compose một lệnh ([ADR-007](adr/ADR-007-docker-packaging.md)) |
| D-15 | Dự án còn dùng cho **CV/portfolio**, nhắm vị trí **Backend Java**; ưu tiên "hoàn thiện, chạy được, chứng minh được" hơn nhiều tính năng |
| D-16 | Không có deadline: roadmap theo thứ tự phase + tiêu chí xong, không ước lượng thời gian |
| D-17 | Trình độ "trên người mới một chút": kế hoạch đi từ dễ đến khó, 1 service trọn vòng trước, rồi service thứ hai, rồi gRPC, rồi message queue; kỹ thuật nâng cao (saga phức tạp, CQRS/event sourcing, service mesh) để ở "mở rộng tùy chọn" |
| D-18 | **Riêng tư presence:** người lạ không được thấy trạng thái online; chỉ **bạn bè** và **người chung ít nhất một server** mới thấy (hướng chung đã chốt; chi tiết hiện thực là đề xuất P-14) |
| D-19 | **Kick/ban thuộc MVP** (FR-036, FR-037, FR-039), quyền `KICK_MEMBERS`, `BAN_MEMBERS`; audit log đầy đủ (FR-038) vẫn Phase 2 |
| D-20 | Người dùng **đã có tài khoản Cloudinary**; cấu hình qua biến môi trường ([infra-structure.md](../04-project-structure/infra-structure.md#4-biến-môi-trường-và-secret)) |
| D-21 | **Lớp DAO** ở `infrastructure/persistence`: domain chỉ có interface `XxxRepository`; `XxxRepositoryImpl` dùng `XxxDao` + `XxxMapper` + `XxxJpaEntity`; transaction ở application service; outbox/inbox cũng qua DAO ([ADR-009](adr/ADR-009-dao-layer.md)) |

## 4. ADR

Các quyết định quan trọng nhất có ADR riêng trong [adr/](adr/README.md): broker (ADR-001), gRPC cho đồng bộ (ADR-002), schema-per-service (ADR-003), Cloudinary signed upload (ADR-004), cấu trúc repo (ADR-005), build tool (ADR-006), đóng gói Docker (ADR-007), không dùng Lombok (ADR-008), lớp DAO (ADR-009).
