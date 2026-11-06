# Kế hoạch backend

File này là checklist công việc backend theo phase và theo service; mỗi mục có tiêu chí hoàn thành, mỗi phase ghi kiến thức cần biết trước, tài liệu nên đọc và khái niệm microservice/DDD sẽ học. Thứ tự phase khớp [roadmap.md](roadmap.md); lộ trình khái niệm + thí nghiệm ở [learning-path.md](learning-path.md).

Ký hiệu: `[ ]` việc cần làm, `[x]` đã xong — "→" tiêu chí xong.

---

## P0 — Nền móng (S)

**Cần biết trước:** Git cơ bản, Maven cơ bản, Docker run/build cơ bản.
**Đọc:** [Docker Compose](https://docs.docker.com/compose/), [Compose startup order](https://docs.docker.com/compose/how-tos/startup-order/), [Maven multi-module](https://maven.apache.org/guides/mini/guide-multiple-modules.html), [GitHub Actions quickstart](https://docs.github.com/en/actions/writing-workflows/quickstart).
**Khái niệm học:** database-per-service bằng schema + quyền DB; hạ tầng như code.

- [x] Parent `pom.xml` + Maven wrapper + module rỗng `proto`, `libs/*` → `./mvnw verify` chạy xanh.
- [x] `infra/compose.infra.yaml`: Postgres (init script), Redis, RabbitMQ có healthcheck → `docker compose up -d` cả 3 `healthy`.
- [x] Init script tạo 4 schema + 4 user → đăng nhập `messaging_svc`, `SELECT * FROM community.x` bị `permission denied`.
- [x] `.env.example`, `.dockerignore`, `infra/scripts/gen-jwt-keys.sh` → làm theo README từ máy sạch được.
- [x] Spotless cấu hình ở parent → `./mvnw spotless:check` chạy trong CI.
- [x] Workflow `proto.yml` rỗng + reusable `_backend-service.yml` khung (xem [devops-plan.md](devops-plan.md)) → CI xanh trên PR rỗng.

**Demo:** `docker compose up -d` + RabbitMQ UI `:15672` + chứng minh cách ly schema.

---

## P1 — Identity trọn vòng (M)

**Cần biết trước:** Spring Boot REST controller, Spring Data JPA cơ bản, SQL.
**Đọc:** [Spring Boot reference](https://docs.spring.io/spring-boot/reference/), [Flyway + Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html#howto.data-initialization.migration-tool.flyway), [Spring Security Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [Testcontainers Java](https://java.testcontainers.org/), [ArchUnit user guide](https://www.archunit.org/userguide/html/000_Index.html), [Docker multi-stage build](https://docs.docker.com/build/building/multi-stage/), Martin Fowler — [Value Object](https://martinfowler.com/bliki/ValueObject.html).
**Khái niệm học:** cấu trúc DDD 4 lớp (domain/application/infrastructure/interfaces), entity vs value object, repository, JWT bất đối xứng + JWKS, refresh token rotation, Dockerfile multi-stage, CI theo service.

| # | Việc | Tiêu chí xong |
|---|------|---------------|
| [x] 1 | Khung `identity-service` theo [backend-structure.md](../04-project-structure/backend-structure.md) + `ArchitectureTest` | ArchUnit fail nếu `domain` import Spring |
| [x] 2 | VO `Email`, `Username`, `DisplayName`, `PasswordHash`; aggregate `User` | Unit test VO (hợp lệ/không hợp lệ) |
| [x] 3 | Flyway `V1__init.sql` (`users`, `refresh_tokens`, `outbox_event`); lớp persistence `UserJpaEntity`, `UserDao`, `UserMapper`, `UserRepositoryImpl` (ADR-009) | Service khởi động, `flyway_schema_history` trong schema `identity`; IT Testcontainers cho `UserRepositoryImpl`; unit test round-trip `UserMapper` |
| [x] 4 | `RegisterUserUseCase` + `POST /auth/register` (FR-001) | IT Testcontainers: trùng username đồng thời → một `201`, một `409` |
| [x] 5 | Ký JWT RS256 + `/.well-known/jwks.json` | Token verify được bằng JWKS trong test |
| [x] 6 | Login / refresh rotation / reuse detection / logout (FR-002..FR-004) | IT: dùng lại refresh token cũ → 401 và cả family bị revoke |
| [x] 7 | Profile `GET/PATCH /users/me`, `GET /users/{id}`, `GET /users?ids=`, `GET /users/by-username/{u}` (FR-006, FR-007, FR-010) | `@WebMvcTest` + IT |
| [x] 8 | Port `UploadSigner` + `CloudinaryUploadSigner` (ACL), avatar signature + lưu (FR-008, FR-100, FR-102) | `publicId` sai folder → 400; test với signer giả, 1 test thủ công với Cloudinary thật (chờ điền key, xem [journal.md](../journal.md)) |
| [x] 9 | Outbox: ghi `UserRegistered`, `UserProfileUpdated` vào `outbox_event` (chưa relay) | Bảng có dòng sau đăng ký (relay làm ở P5) |
| [x] 10 | Problem Details + `libs/nexus-common-web` | Lỗi validate trả đúng format [api-conventions.md](../02-architecture/api-conventions.md#4-định-dạng-lỗi) |
| [x] 11 | Log JSON, actuator liveness/readiness, springdoc | `/swagger-ui.html` chạy; log có `requestId` |
| [x] 12 | `Dockerfile` + service trong `compose.app.yaml` | `docker compose up` → identity `healthy`, non-root (`docker exec id`) |
| [x] 13 | Workflow `identity-service.yml` | PR chạy test + build image + Trivy; main push GHCR (chờ xác nhận CI sau khi push) |

**Demo:** đăng ký/đăng nhập trên frontend tối giản (gọi thẳng identity :8081 ở phase này), xem image trên GHCR.

---

## P2 — Community core + API Gateway (M)

**Cần biết trước:** P1.
**Đọc:** [Spring Cloud Gateway (Server WebFlux)](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux.html), Fowler — [Bounded Context](https://martinfowler.com/bliki/BoundedContext.html), [DDD Aggregate](https://martinfowler.com/bliki/DDD_Aggregate.html), Vaughn Vernon — [Effective Aggregate Design](https://www.dddcommunity.org/library/vernon_2011/), [PostgreSQL concurrency control](https://www.postgresql.org/docs/current/mvcc.html).
**Khái niệm học:** service thứ hai và ranh giới dữ liệu; **API Gateway** (định tuyến, cross-cutting); xác thực phân tán không cần gọi Identity (JWKS); thiết kế aggregate nhỏ; **concurrency** bằng câu lệnh nguyên tử + unique constraint; rate limiting bằng Redis; log tập trung.

| # | Việc | Tiêu chí xong |
|---|------|---------------|
| [x] 1 | Khung `community-service`, Flyway (`servers, roles, categories, channels, members, member_roles, invites, channel_permission_overwrites, outbox_event`) | ArchUnit xanh; migration chạy |
| [x] 2 | Aggregate `Server` (+ `@everyone`), `Category`, `Channel`, `Member`, `Invite`; VO `ServerName`, `ChannelName`, `InviteCode`, `PermissionSet` | Unit test bất biến (tên kênh sai → lỗi; `@everyone` không xóa được) |
| [x] 3 | Tạo server (FR-020) — 1 transaction, 4 event vào outbox | IT: tạo xong có đủ role/category/kênh/member + 4 dòng outbox |
| [x] 4 | Sửa/xóa server, danh sách server (FR-021..FR-023) — tạm chỉ owner được quản lý | IT |
| [x] 5 | Category/kênh CRUD + sắp xếp (FR-024, FR-025) | IT |
| [x] 6 | Invite: tạo, preview, accept, thu hồi (FR-026..FR-029) | IT **đồng thời**: 20 thread join invite `maxUses=5` → đúng 5 member mới |
| [x] 7 | Rời server, danh sách member cursor (FR-030, FR-031) | IT |
| [x] 8 | Resource server JWT qua JWKS của Identity | Gọi với token Identity cấp → 200; token tự ký → 401 |
| [x] 9 | `api-gateway`: route theo [services.md](../02-architecture/services.md#7-api-gateway), verify JWT, CORS, `X-Request-Id`, gom OpenAPI | Frontend chỉ dùng `:8080` |
| [x] 10 | Rate limit `RequestRateLimiter` + Redis cho `/auth/**` (NFR-013) | 11 request/phút cùng IP → 429 |
| [x] 11 | Dockerfile + compose + workflow cho community và api-gateway | CI xanh, compose healthy (chờ xác nhận CI sau khi push) |
| [x] 12 | Bật `otel-lgtm` (profile observability), log + metric qua OTLP | Grafana → Loki lọc theo `service_name` và `requestId` thấy log của api-gateway và service đích cho 1 request (mục tiêu 3 thành phần chờ P4, khi có service gọi service) |

**Demo:** `infra/scripts/demo-p2.sh` (A tạo server + invite, B xem trước, join, rời; C bị 410); Grafana thấy log gateway và service đích.

---

## P3 — Role, permission & kick/ban (M)

**Cần biết trước:** bitwise trong Java.
**Đọc:** [permission-model.md](../01-requirements/permission-model.md), [Discord permissions docs](https://discord.com/developers/docs/topics/permissions) (tham khảo mô hình).
**Khái niệm học:** **domain service** thuần (`PermissionCalculator`), test dạng bảng, chính sách (policy) trong domain, optimistic locking.

| # | Việc | Tiêu chí xong |
|---|------|---------------|
| [x] 1 | `PermissionCalculator` + `RoleHierarchyPolicy` (domain, không Spring); gRPC `PermissionService` dùng lại `PermissionCalculator` ở P4 | ≥ 15 case unit test bao phủ từng bước thuật toán |
| [x] 2 | Role CRUD + sắp xếp (FR-032) | IT luật thứ bậc: không sửa được role ngang/cao hơn mình |
| [x] 3 | Gán role (FR-033), overwrite kênh (FR-034); event `MemberRolesChanged`, `ChannelPermissionsChanged`, `Role*` vào outbox | IT |
| [x] 4 | Thay check "chỉ owner" ở P2 bằng permission thật (`MANAGE_*`, `CREATE_INVITE`) | IT cho mỗi endpoint quản trị: thiếu quyền → 403 |
| [x] 5 | `GET /servers/{id}/permissions/@me`; `GET /servers/{id}` lọc kênh theo `VIEW_CHANNEL` | Kênh bị ẩn không xuất hiện |
| [x] 6 | Optimistic lock `version` trên `Server`, `Channel` | Hai PATCH đồng thời → một `409 VERSION_CONFLICT` |

**Phần 2 — Kick/ban (làm sau khi phần role/permission ở trên xong, vì dùng lại `RoleHierarchyPolicy`):**

| # | Việc | Tiêu chí xong |
|---|------|---------------|
| [x] 7 | Flyway `server_bans`; aggregate `ServerBan`; mở rộng `RoleHierarchyPolicy.canModerate(actor, target)` | Unit test: không kick/ban owner, chính mình, người ngang/cao hơn ([activity-kick-ban-check.md](../03-diagrams/activity-kick-ban-check.md)) |
| [x] 8 | Kick `DELETE /servers/{id}/members/{userId}` (FR-036) + outbox `MemberKicked` | IT: kick xong join lại bằng invite được |
| [x] 9 | Ban `PUT /servers/{id}/bans/{userId}` (FR-037) idempotent + outbox `MemberBanned` | IT: ban xong join → `403 BANNED_FROM_SERVER`; ban 2 lần → 1 dòng, 1 event |
| [x] 10 | Danh sách ban + gỡ ban (FR-039) + outbox `MemberUnbanned` | IT: gỡ ban → join được |
| [x] 11 | Advisory lock theo cặp `(server, user)` ở join, ban, gỡ ban (WF-12 bước 4) | IT đồng thời: 50 lần chạy song song "ban" và "join" → không lần nào kết thúc với member bị ban |

**Khái niệm học thêm (phần 2):** policy dùng lại giữa nhiều use case, race condition giữa hai thao tác khác nhau và cách tuần tự hóa theo khóa nghiệp vụ (advisory lock), xóa mềm vs xóa cứng, "audit tối thiểu" bằng chính dữ liệu nghiệp vụ.

**Demo:** `infra/scripts/demo-p3.sh` chạy kịch bản kênh `announcements` ở [permission-model.md](../01-requirements/permission-model.md#7-ví-dụ) + kịch bản ban ở [sequence-kick-ban.md](../03-diagrams/sequence-kick-ban.md).

---

## P4 — Messaging + gRPC (L) — *phase học gRPC*

**Cần biết trước:** P3; HTTP/2 là gì (đọc lướt).
**Đọc:** [gRPC core concepts](https://grpc.io/docs/what-is-grpc/core-concepts/), [gRPC Java basics](https://grpc.io/docs/languages/java/basics/), [Protobuf proto3 guide](https://protobuf.dev/programming-guides/proto3/), [Proto best practices](https://protobuf.dev/best-practices/dos-donts/), [gRPC deadlines](https://grpc.io/docs/guides/deadlines/), [gRPC status codes](https://grpc.io/docs/guides/status-codes/), [gRPC retry](https://grpc.io/docs/guides/retry/), [Spring gRPC](https://docs.spring.io/spring-grpc/reference/), [Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker), [buf docs](https://buf.build/docs/), [Spring Data Redis](https://docs.spring.io/spring-data/redis/reference/), [PostgreSQL indexes](https://www.postgresql.org/docs/current/indexes.html).
**Khái niệm học:** giao tiếp đồng bộ, hợp đồng proto, sinh code, **deadline**, **retry**, **circuit breaker**, fail-closed, coupling thời gian chạy, **service discovery bằng DNS**, cache-aside với Redis, keyset pagination, idempotency key (`nonce`), distributed tracing qua gRPC.

| # | Việc | Tiêu chí xong |
|---|------|---------------|
| 1 | `proto/` module + `community/v1/permission_service.proto`, `identity/v1/user_service.proto`, `common/v1/errors.proto`; `buf.yaml` | `./mvnw -pl proto install` sinh stub; `buf lint` sạch |
| 2 | `libs/nexus-common-grpc`: interceptor JWT, `x-request-id`, MDC, map exception ↔ status | Unit test interceptor |
| 3 | Community: `PermissionGrpcService` (`CheckChannelPermissions`, `CheckServerMembership`) dùng `PermissionCalculator` | Test in-process + `grpcurl` thủ công |
| 4 | Identity: `UserGrpcService` (`GetUser`, `BatchGetUsers`) | Test in-process |
| 5 | Khung `messaging-service`, Flyway (`channel_refs, messages, message_attachments, message_mentions, reactions, outbox_event, processed_event`) | ArchUnit xanh |
| 6 | `GrpcChannelPermissionChecker`: deadline 300 ms, retry `UNAVAILABLE` ×2, circuit breaker | Test: server giả chậm 1 s → `DependencyUnavailableException` < 400 ms; 10 lỗi liên tiếp → circuit `OPEN` |
| 7 | Cache Redis kết quả quyền, chỉ TTL 30 s (P5 chuyển sang key có version theo server + TTL 60 s như [data-model.md](../02-architecture/data-model.md#6-redis-dùng-chung-khác)) | Metric `nexus_permission_cache_hits_total` tăng; gọi lần 2 không có gRPC span |
| 8 | Gửi tin (FR-050, FR-053, FR-059 lưu mention) với `nonce` idempotent; `channel_refs` ghi từ kết quả gRPC (`server_id`) | IT: gửi 2 lần cùng `nonce` → 1 tin |
| 9 | Sửa/xóa (FR-051, FR-052), optimistic lock | IT 2 lần sửa đồng thời → 1 `409` |
| 10 | Reaction (FR-054) `ON CONFLICT DO NOTHING` | IT 50 thread cùng react 1 emoji → 1 dòng |
| 11 | Lịch sử keyset (FR-055) + index `(channel_id, id DESC)` | `EXPLAIN` dùng Index Scan |
| 12 | Chữ ký đính kèm + lưu attachment (FR-058, FR-101, FR-102) | `publicId` của user khác → 400 |
| 13 | Map lỗi gRPC → `503 DEPENDENCY_UNAVAILABLE` / 404 / 403 | `docker compose stop community-service` → gửi tin trả 503 trong < 1 s (khi cache trống) |
| 14 | Tracing OTel cho gRPC | Tempo thấy span `messaging → community` trong cùng trace |
| 15 | Dockerfile + compose + workflow messaging; `proto.yml` chạy `buf breaking` | Đổi số field trong PR thử → CI đỏ |

**Bài tập gRPC (làm và ghi kết quả vào `docs/journal.md`):**
1. Gọi `CheckChannelPermissions` bằng `grpcurl` với token hợp lệ / hết hạn / không token — ghi status nhận được.
2. Thêm `Thread.sleep(500)` vào Community → quan sát `DEADLINE_EXCEEDED` ở Messaging và span đỏ trên Tempo.
3. Tắt Community, gửi 20 tin → xem trạng thái circuit breaker trên Grafana chuyển `CLOSED → OPEN → HALF_OPEN`.
4. Thêm field mới vào response (tương thích) rồi thử đổi kiểu field (không tương thích) → thấy `buf breaking` phân biệt.
5. `docker compose up --scale community-service=2` → xác nhận `round_robin` chia request (log 2 instance).

**Demo (M1):** đăng nhập → tạo server → chat (poll 3 s) qua 3 service + gateway; tắt Community → lỗi 503 có kiểm soát.

---

## P5 — Message queue (L) — *phase học message queue*

**Cần biết trước:** P4; transaction DB là gì.
**Đọc:** [RabbitMQ tutorials 1-5 (Java)](https://www.rabbitmq.com/tutorials), [AMQP 0-9-1 model](https://www.rabbitmq.com/tutorials/amqp-concepts), [Consumer ack & publisher confirms](https://www.rabbitmq.com/docs/confirms), [Dead letter exchanges](https://www.rabbitmq.com/docs/dlx), [Quorum queues](https://www.rabbitmq.com/docs/quorum-queues), [Reliability guide](https://www.rabbitmq.com/docs/reliability), [Spring AMQP reference](https://docs.spring.io/spring-amqp/reference/), microservices.io — [Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html), [Idempotent consumer](https://microservices.io/patterns/communication-style/idempotent-consumer.html), [messaging-deep-dive.md](../02-architecture/messaging-deep-dive.md).
**Khái niệm học:** exchange/queue/binding, routing key, competing consumers, ack/nack, prefetch, retry + DLQ, at-least-once, **outbox**, **inbox/idempotent consumer**, **eventual consistency**, projection/read model, ordering, choreography (phản ứng dây chuyền qua event), cache invalidation phân tán.

| # | Việc | Tiêu chí xong |
|---|------|---------------|
| 1 | `libs/nexus-common-messaging`: envelope, `OutboxWriter` (`DomainEventPublisher` port), `OutboxRelay` (`FOR UPDATE SKIP LOCKED`, publisher confirm), `InboxGuard` | Unit + IT RabbitMQ Testcontainers: publish → consume → ack |
| 2 | Bật relay ở identity, community, messaging; exchange `<svc>.events` | RabbitMQ UI thấy message rate trên 3 exchange |
| 3 | Messaging consumer `messaging.community.events`: `ChannelCreated/Deleted`, `ServerDeleted` → `channel_refs`; event đổi quyền (kể cả `MemberKicked`, `MemberBanned`) → `INCR perm:ver:{serverId}` (cache version) | IT: xóa kênh → gửi tin vào kênh đó → 404 |
| 4 | Retry 3 lần + DLQ cho mọi queue; lỗi parse vào DLQ ngay | IT: payload hỏng → nằm ở `.dlq`, queue chính không bị kẹt |
| 5 | Inbox `processed_event` cho mọi consumer | IT: publish cùng `eventId` 2 lần → xử lý 1 lần |
| 6 | Trace propagation qua AMQP (`traceparent` trong outbox headers) | Tempo: 1 trace gồm REST → DB → publish → consume |
| 7 | Metric `nexus_outbox_pending`, queue depth trên Grafana | Dừng RabbitMQ 1 phút → gauge tăng, bật lại → về 0 |
| 8 | Graceful shutdown listener (NFR-026) | `docker compose stop messaging-service` khi đang xử lý → không mất message |
| 9 | Contract test: `contracts/events/*.json` cho mọi event đã phát | Producer test + consumer test xanh |

**Bài tập message queue:**
1. Tắt `messaging-service`, tạo 3 kênh ở Community, xem message nằm chờ trong `messaging.community.events`; bật lại → `channel_refs` đủ 3 dòng (eventual consistency).
2. Dùng UI RabbitMQ "Publish message" gửi lại đúng 1 event đã có → consumer bỏ qua (inbox).
3. Gửi payload sai → theo dõi 3 lần retry trong log rồi message vào DLQ; sửa code, "move" message về queue gốc.
4. Thêm `Thread.sleep` + `kill -9` consumer giữa lúc xử lý → message được giao lại (`redelivered=true`).
5. Dừng RabbitMQ khi đang tạo server → request vẫn `201`; bật lại → event được relay.
6. Chạy 2 instance messaging → quan sát competing consumers chia message, và vì sao cache quyền phải ở Redis chứ không ở bộ nhớ.

**Demo:** chạy bài tập 1, 3, 5 trước người xem với RabbitMQ UI + Grafana.

---

## P6 — Realtime + Presence (L)

**Cần biết trước:** P5; WebSocket là gì.
**Đọc:** [Spring STOMP over WebSocket](https://docs.spring.io/spring-framework/reference/web/websocket/stomp.html), [STOMP 1.2 spec](https://stomp.github.io/stomp-specification-1.2.html), [Redis data types](https://redis.io/docs/latest/develop/data-types/), [Redis Lua scripting](https://redis.io/docs/latest/develop/interact/programmability/eval-intro/), [realtime-design.md](../02-architecture/realtime-design.md).
**Khái niệm học:** gateway stateful vs service stateless, fan-out nhiều instance, queue riêng mỗi instance (exclusive/auto-delete), state tạm trong Redis + nguyên tử bằng Lua, heartbeat/phát hiện lỗi, at-most-once (typing).

| # | Việc | Tiêu chí xong |
|---|------|---------------|
| 1 | `messaging/v1/channel_access_service.proto` + `ChannelAccessGrpcService` ở Messaging | Test in-process (kênh server, DM sau P7) |
| 2 | `realtime-gateway`: `/ws`, auth CONNECT, kiểm tra SUBSCRIBE qua gRPC | Subscribe kênh không có quyền → ERROR frame |
| 3 | Queue riêng mỗi instance + map event → destination ([bảng](../02-architecture/realtime-design.md#3-scale-nhiều-instance-p-13)) | 2 instance, client ở 2 instance nhận tin của nhau |
| 4 | Thu hồi subscription khi quyền đổi | Gỡ role → client mất subscription ≤ 1 s |
| 5 | Typing (FR-057) qua `TypingStarted` non-persistent | Hiện "đang gõ" ở client khác |
| 6 | `presence-service`: consumer `presence.realtime.events`, Lua script, job dọn instance chết | `kill -9` 1 instance gateway → user của nó OFFLINE ≤ 90 s |
| 6a | Projection quan hệ phần **server**: consumer `presence.community.events`, gRPC `ListServerIdsForUser` (thêm RPC ở Community, `x-service-token` — P-36), nạp bù lười + TTL 24 h | IT: xóa key Redis → lần kết nối sau nạp lại đúng |
| 6b | `PresenceAudiencePolicy`; `PresenceChanged` mang `serverIds`; realtime giao qua `/topic/servers/{id}`; `GET /presence` lọc | **IT riêng tư (NFR-018):** X không chung server → không nhận event, `GET /presence` không trả A; B bị ban khỏi server chung duy nhất → hết nhận |
| 7 | Gateway route `/ws/**`; Dockerfile + compose + workflow cho realtime-gateway, presence | CI xanh |

**Demo (M2):** 2 trình duyệt, 2 instance gateway, chat realtime + typing + online/offline.

---

## P7 — Social & DM (M)

**Cần biết trước:** P6.
**Đọc:** [activity-friend-request-lifecycle.md](../03-diagrams/activity-friend-request-lifecycle.md), [PostgreSQL partial indexes](https://www.postgresql.org/docs/current/indexes-partial.html).
**Khái niệm học:** aggregate có máy trạng thái, gRPC sang Identity, module hóa bên trong một service (sẵn sàng tách `social-service`).

| # | Việc | Tiêu chí xong |
|---|------|---------------|
| 1 | Module `social`: `FriendRequest`, `Friendship`, `DmChannel`; Flyway | Unit test mọi chuyển trạng thái hợp lệ/không hợp lệ |
| 2 | API bạn bè (FR-070..FR-074) + gRPC `GetUser` | IT: A và B mời nhau đồng thời → thành bạn đúng 1 lần (partial unique index) |
| 3 | DM (FR-075, FR-076), `DmAccessPolicy`, `channel_refs kind=DM`; `CheckChannelAccess` hỗ trợ DM | Hủy kết bạn → gửi DM 403, đọc vẫn được |
| 4 | Event `FriendRequest*`, `FriendRemoved`, `DmChannelCreated` → realtime `/user/queue/events` | Lời mời hiện realtime |
| 4a | Presence phần **bạn bè**: `messaging.v1.SocialService/ListFriendIds`, consumer `presence.messaging.events`, `PresenceChanged.friendIds` → `/user/queue/events` | IT: bạn không chung server thấy online; hủy kết bạn → hết thấy (FR-083) |
| 5 | ArchUnit: `social` không import `message` internals ngoài port chung | Test xanh |

---

## P8 — Notification (M)

**Khái niệm học:** service thuần event-driven, nhiều projection, consumer chịu đảo thứ tự (`GREATEST`), ghi theo lô.

| # | Việc | Tiêu chí xong |
|---|------|---------------|
| 1 | `notification-service`, Flyway (`channel_activity, server_members, read_states, mentions, outbox_event, processed_event`) | ArchUnit xanh |
| 2 | Consumer `community.events` và `messaging.events` theo [bảng binding](../02-architecture/event-catalog.md#8-ma-trận-binding-queue--exchange-routing-key) | IT với RabbitMQ Testcontainers |
| 3 | Unread + mention (FR-090, FR-091, FR-093); mention chỉ tính nếu là member/participant | IT: mention người ngoài server không tăng đếm |
| 4 | `PUT /read-states/{channelId}` (FR-092) chỉ tăng; `ReadStateUpdated`, `MentionCreated` | IT: gửi `lastReadMessageId` cũ hơn → không lùi |
| 5 | Event đến sai thứ tự | IT: `MessageCreated` id lớn trước id nhỏ → `last_message_id` đúng |
| 6 | Dockerfile + compose + workflow | CI xanh |

**Demo (M3):** đầy đủ MVP.

---

## P9 — Hardening & portfolio (M)

**Đọc:** [k6 docs](https://grafana.com/docs/k6/latest/), [k6 WebSocket](https://grafana.com/docs/k6/latest/javascript-api/k6-websockets/), [PostgreSQL EXPLAIN](https://www.postgresql.org/docs/current/using-explain.html), [Grafana dashboards](https://grafana.com/docs/grafana/latest/dashboards/).
**Khái niệm học:** đo trước khi tối ưu, p95/p99, bottleneck, giới hạn tài nguyên container.

| # | Việc | Tiêu chí xong |
|---|------|---------------|
| 1 | Script seed 1 triệu tin vào 1 kênh | Chạy < 5 phút |
| 2 | `EXPLAIN ANALYZE` keyset vs `OFFSET` (NFR-003) | Bảng số liệu trong `docs/perf/history.md` |
| 3 | k6 `send-message.js` (NFR-001), `ws-fanout.js` (NFR-002) | Báo cáo p95, throughput, cấu hình máy trong `docs/perf/` |
| 4 | So sánh có/không cache quyền Redis | Số liệu p95 + số gRPC call/giây |
| 5 | Rate limit gửi tin (NFR-013) | k6 vượt ngưỡng → 429 |
| 6 | Dashboard "Nexus overview" + 3 alert đơn giản | JSON commit trong repo |
| 7 | Giới hạn bộ nhớ container (NFR-005) | `docker stats` tổng ≤ 6 GB |
| 8 | README, ảnh trace, GIF, ADR hoàn chỉnh; demo công khai | Đạt [portfolio-readiness.md](portfolio-readiness.md) |

---

## Bảng tổng hợp theo service

| Service | P1 | P2 | P3 | P4 | P5 | P6 | P7 | P8 |
|---------|----|----|----|----|----|----|----|----|
| identity | Toàn bộ | JWKS cho service khác | | gRPC `UserService` | Relay outbox | | | |
| community | | Core + invite | Role, permission, kick/ban | gRPC `PermissionService` | Relay outbox | RPC `ListServerIdsForUser` | | |
| api-gateway | | Route, JWT, CORS, rate limit | | | | Route `/ws` | | |
| messaging | | | | Core + gRPC client + Redis cache | Consumer, `channel_refs`, cache version | gRPC `ChannelAccessService` | Social, DM, gRPC `SocialService` | |
| realtime-gateway | | | | | | Toàn bộ | User queue friend/DM | User queue read/mention |
| presence | | | | | | Session, projection server, lọc riêng tư | Projection bạn bè | |
| notification | | | | | | | | Toàn bộ |
