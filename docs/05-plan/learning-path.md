# Lộ trình học

File này là lộ trình học theo thứ tự, gắn từng khái niệm microservice/DDD vào phase triển khai cụ thể, kèm kiến thức cần biết trước, tài liệu nên đọc (ưu tiên tài liệu chính thức) và các thí nghiệm nên thử. Dành cho trình độ "trên người mới một chút": đã biết Spring Boot, Docker, broker cơ bản; chưa làm hệ thống phân tán thật.

Ba trọng tâm: **(1) kiến trúc microservice, (2) service giao tiếp với nhau ra sao, (3) message queue hoạt động thế nào.**

## 1. Bản đồ khái niệm → phase

| Khái niệm | Hiểu ngắn gọn | Gặp ở đâu trong Nexus | Phase |
|----------|---------------|-----------------------|-------|
| Database per service | Mỗi service sở hữu dữ liệu, không ai khác đụng vào | Schema + user riêng, cấm query chéo | P0 |
| Container hóa | Đóng gói app + runtime thành image chạy giống nhau mọi nơi | Dockerfile multi-stage mỗi service | P1 |
| CI theo service | Mỗi service build/test/deploy độc lập | Workflow + path filter | P1 |
| Layered architecture / hexagonal | Domain ở giữa, framework ở rìa | `domain/application/infrastructure/interfaces` + ArchUnit | P1 |
| Entity, value object, repository | (xem mục 3) | `User`, `Email`, `UserRepository` | P1 |
| Xác thực phân tán | Service tự verify token, không gọi Identity mỗi request | JWT RS256 + JWKS | P1-P2 |
| Bounded context | Ranh giới mà trong đó một từ có một nghĩa | "Channel" khác nhau ở Community/Messaging/Notification | P2 |
| API Gateway | Một cửa vào: route, auth, rate limit, CORS | `api-gateway` | P2 |
| Aggregate | Cụm đổi cùng nhau, một transaction | `Server`+`Role`, `Member`, `Invite` | P2 |
| Concurrency ở DB | Dựa vào câu lệnh nguyên tử và constraint, không lock ứng dụng | Invite `uses`, unique username | P1-P2 |
| Log tập trung | Log mọi service về một chỗ, tìm theo `requestId` | Loki trong `otel-lgtm` | P2 |
| Domain service | Logic cần nhiều aggregate | `PermissionCalculator` | P3 |
| Policy dùng lại | Một luật nghiệp vụ dùng cho nhiều use case | `RoleHierarchyPolicy` cho gán role và kick/ban | P3 |
| Race giữa hai thao tác | Hai transaction khác nhau xen kẽ phá bất biến | Ban vs join → advisory lock theo cặp `(server, user)` | P3 |
| Optimistic locking | Phát hiện ghi đè bằng `version` | `Channel`, `Message` | P3-P4 |
| Giao tiếp đồng bộ (gRPC) | Gọi và chờ trả lời, hợp đồng proto | Messaging → Community | P4 |
| Service discovery | Tìm địa chỉ service khác | DNS của Docker Compose, `dns:///` | P4 |
| Timeout / deadline | Không chờ vô hạn | 300 ms cho check quyền | P4 |
| Retry | Thử lại lỗi tạm thời, chỉ với thao tác an toàn | `UNAVAILABLE`, tối đa 2 lần | P4 |
| Circuit breaker | Ngừng gọi service đang chết để không kéo sập mình | Resilience4j | P4 |
| Fail-closed vs fail-open | Khi không chắc thì từ chối hay cho qua | Quyền → fail-closed; typing → bỏ qua | P4 |
| Cache-aside | Đọc cache, miss thì gọi nguồn rồi ghi cache | Redis cache quyền | P4 |
| Idempotency key | Client gửi lại không tạo trùng | `nonce` khi gửi tin | P4 |
| Distributed tracing | Theo một request qua nhiều service | Tempo: gateway → messaging → community | P4 |
| Giao tiếp bất đồng bộ (event) | Thông báo điều đã xảy ra, không chờ | `MessageCreated` | P5 |
| Exchange / queue / binding | Cách RabbitMQ định tuyến | `messaging.events` → `notification.messaging.events` | P5 |
| Ack, retry, DLQ | Xác nhận, thử lại, nơi chứa message hỏng | Mọi consumer | P5 |
| Outbox pattern | Ghi event cùng transaction với dữ liệu | `outbox_event` + relay | P5 |
| Idempotent consumer (inbox) | Xử lý trùng không gây hại | `processed_event` | P5 |
| Eventual consistency | Dữ liệu các service khớp nhau *sau một lúc* | `channel_refs` | P5 |
| Projection / read model | Bản sao cục bộ dựng từ event | `channel_refs`, `channel_activity` | P5, P8 |
| Choreography | Nhiều service phản ứng dây chuyền với event, không ai điều phối | Xóa server → Messaging, Notification tự dọn | P5 |
| Competing consumers | Nhiều instance chia nhau một queue | Scale messaging | P5 |
| Stateful gateway & fan-out | Giữ kết nối, nhân bản event tới mọi instance | `realtime-gateway` | P6 |
| Projection + nạp bù lười (lazy backfill) | Bản chiếu dựng từ event, thiếu thì hỏi nguồn qua gRPC, tự làm mới theo TTL | Presence `RelationshipProjection` | P6, P7 |
| Privacy theo quan hệ | Quyết định "ai được thấy" dựa trên dữ liệu của service khác | Presence chỉ tới bạn bè + người chung server | P6, P7 |
| Xác thực service-to-service | Gọi gRPC khi không có user | `x-service-token` + allowlist (P-36) | P6 |
| Heartbeat / failure detection | Phát hiện thành phần chết im lặng | `GatewayHeartbeat` | P6 |
| At-most-once | Mất cũng được | Typing | P6 |
| Tách module trong service | Chuẩn bị tách service sau | `social` trong Messaging | P7 |
| Service thuần event-driven | Không ai gọi nó đồng bộ | Notification | P8 |
| Load test, p95 | Đo thay vì đoán | k6 | P9 |

## 2. Theo phase: biết trước, đọc, thí nghiệm

| Phase | Cần biết trước | Đọc (chính thức) | Thí nghiệm nên thử |
|-------|---------------|------------------|--------------------|
| P0 | Git, Docker run/build, Maven cơ bản | [Docker Compose](https://docs.docker.com/compose/), [Postgres privileges](https://www.postgresql.org/docs/current/ddl-priv.html) | Đăng nhập `messaging_svc` thử đọc schema `community` → bị từ chối |
| P1 | Spring Boot REST, JPA, SQL | [Spring Boot](https://docs.spring.io/spring-boot/reference/), [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [Flyway](https://documentation.red-gate.com/flyway), [Dockerfile best practices](https://docs.docker.com/build/building/best-practices/), [GitHub Actions](https://docs.github.com/en/actions) | Sửa 1 dòng Java, `docker build` lại, xem layer nào được dùng cache; giải mã JWT trên jwt.io và giải thích vì sao service khác verify được mà không gọi Identity |
| P2 | P1 | [Spring Cloud Gateway](https://docs.spring.io/spring-cloud-gateway/reference/), Fowler [Bounded Context](https://martinfowler.com/bliki/BoundedContext.html), [DDD Aggregate](https://martinfowler.com/bliki/DDD_Aggregate.html) | Tắt identity-service rồi gọi API community với token còn hạn → vẫn chạy (vì JWKS đã cache) — giải thích; 20 người join invite 5 lượt cùng lúc |
| P3 | Bitwise | [permission-model.md](../01-requirements/permission-model.md), [PostgreSQL advisory locks](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS) | Viết test bảng trước, code sau (TDD) cho `PermissionCalculator`; chạy song song ban và join khi **chưa** có advisory lock để tận mắt thấy race, rồi thêm lock |
| P4 | HTTP/2 sơ lược | [gRPC concepts](https://grpc.io/docs/what-is-grpc/core-concepts/), [Protobuf](https://protobuf.dev/programming-guides/proto3/), [Deadlines](https://grpc.io/docs/guides/deadlines/), [Status codes](https://grpc.io/docs/guides/status-codes/), [Spring gRPC](https://docs.spring.io/spring-grpc/reference/), [Resilience4j](https://resilience4j.readme.io/docs) | **Tắt Community** → gửi tin trả 503 nhanh; làm chậm Community → thấy `DEADLINE_EXCEEDED`; xem circuit breaker đổi trạng thái; cache Redis cho phép gửi tin khi Community chết; `--scale community-service=2` |
| P5 | Transaction DB | [RabbitMQ tutorials](https://www.rabbitmq.com/tutorials), [AMQP concepts](https://www.rabbitmq.com/tutorials/amqp-concepts), [Confirms](https://www.rabbitmq.com/docs/confirms), [DLX](https://www.rabbitmq.com/docs/dlx), [Spring AMQP](https://docs.spring.io/spring-amqp/reference/), [Outbox](https://microservices.io/patterns/data/transactional-outbox.html) | **Gửi event trùng** từ UI RabbitMQ; **consumer chết giữa chừng** (`kill -9`); tắt broker khi đang ghi; message hỏng → DLQ → move về; tắt consumer 5 phút rồi bật xem queue xả |
| P6 | WebSocket | [Spring STOMP](https://docs.spring.io/spring-framework/reference/web/websocket/stomp.html), [Redis Lua](https://redis.io/docs/latest/develop/interact/programmability/eval-intro/), [Redis sets](https://redis.io/docs/latest/develop/data-types/sets/) | `kill -9` 1 instance realtime-gateway → client reconnect sang instance khác, presence tự dọn sau 90 s; 2 tab ở 2 instance; xóa projection trong Redis rồi kết nối lại → nạp bù qua gRPC; dùng tài khoản người lạ kiểm tra không thấy presence |
| P7 | P6 | [Partial index](https://www.postgresql.org/docs/current/indexes-partial.html) | A và B gửi lời mời cho nhau cùng lúc |
| P8 | P5 | [messaging-deep-dive.md](../02-architecture/messaging-deep-dive.md) | Gửi `MessageCreated` theo thứ tự ngược → unread vẫn đúng; tắt notification 10 phút rồi bật |
| P9 | P1-P8 | [k6](https://grafana.com/docs/k6/latest/), [EXPLAIN](https://www.postgresql.org/docs/current/using-explain.html) | Keyset vs OFFSET trên 1 triệu dòng; có/không cache quyền; giới hạn RAM container thấp xem điều gì xảy ra |

## 3. DDD chiến thuật bằng ví dụ Nexus

| Khái niệm | Ví dụ | Câu hỏi tự kiểm tra |
|----------|-------|---------------------|
| **Entity** | `Role` có `id`; đổi tên/quyền vẫn là role đó | Hai role cùng tên có phải một không? (Không — khác id) |
| **Value object** | `ChannelName("general")` tự kiểm tra chữ thường, ≤100 ký tự; `PermissionSet` bất biến, `allow()` trả object mới | Vì sao `Email` không có `setValue()`? |
| **Aggregate** | `Message` + `Attachment` + `Mention`: thêm attachment phải qua `message.attach(...)` để root kiểm tra ≤5 | Vì sao `Reaction` **không** nằm trong aggregate `Message`? (Tránh tranh chấp `version` khi nhiều người react) |
| **Aggregate root** | `FriendRequest.accept(actorId)` từ chối nếu actor không phải addressee hoặc trạng thái không `PENDING` | Ai được phép gọi `UPDATE friend_requests` trực tiếp? (Chỉ repository khi lưu aggregate) |
| **Domain event** | `MemberJoined` được `Member.join(...)` ghi nhận, use case chuyển vào outbox | Domain event khác integration event thế nào? (Integration event = domain event + envelope, phát ra ngoài) |
| **Repository** | `MessageRepository.save(Message)` / `findById` — theo aggregate, không theo bảng; hiện thực `MessageRepositoryImpl` dùng `MessageDao` + `MessageMapper` ([ADR-009](../00-overview/adr/ADR-009-dao-layer.md)) | Vì sao không có `AttachmentRepository`? Repository khác DAO ở đâu? (Repository nói ngôn ngữ nghiệp vụ và aggregate; DAO nói ngôn ngữ bảng và JPA entity) |
| **Domain service** | `PermissionCalculator.compute(member, server, channel)` | Vì sao không đặt trong `Member`? |
| **Bounded context** | Community `Channel` (tên, vị trí, overwrite) vs Messaging `ChannelRef` (id, loại, serverId) | Vì sao Messaging không lưu tên kênh? |
| **Anti-corruption layer** | `CloudinaryUploadSigner`, adapter gRPC dịch proto → `PermissionSet` | Nếu đổi Cloudinary sang S3, sửa những file nào? |

## 4. Thứ tự đọc tài liệu dự án

1. [vision-and-goals.md](../00-overview/vision-and-goals.md) → [glossary.md](../00-overview/glossary.md)
2. [architecture-overview.md](../02-architecture/architecture-overview.md) → [component-diagram.md](../03-diagrams/component-diagram.md)
3. [bounded-contexts-and-context-map.md](../02-architecture/bounded-contexts-and-context-map.md) → [services.md](../02-architecture/services.md)
4. Trước P4: [grpc-design.md](../02-architecture/grpc-design.md). Trước P5: [messaging-deep-dive.md](../02-architecture/messaging-deep-dive.md), [event-catalog.md](../02-architecture/event-catalog.md)
5. Mỗi phase: mục tương ứng trong [backend-plan.md](backend-plan.md)

## Mở rộng tùy chọn

Không nằm trên đường chính; chỉ làm sau M4 (hoặc khi muốn thử riêng một nhánh git).

| Chủ đề | Hướng mở rộng | Vì sao để sau |
|-------|---------------|---------------|
| Kafka | Thay lớp `infrastructure/messaging` bằng Spring Kafka, giữ outbox/inbox; thử replay offset để dựng lại `channel_activity` | Phức tạp hơn RabbitMQ (partition, rebalance) |
| Saga orchestration có bù trừ | "Ban + xóa toàn bộ tin nhắn của user trong server" (backlog): Community ban → Messaging xóa mềm tin → Notification dọn mention; thêm bước bù khi một bước thất bại | MVP không có giao dịch xuyên service cần bù trừ |
| CQRS / event sourcing | Lưu `FriendRequest` dưới dạng chuỗi event | Tăng độ phức tạp mạnh, ít lợi ích ở quy mô này |
| Service discovery động | Eureka hoặc Consul thay DNS compose | Compose DNS đã đủ |
| Kubernetes | Helm chart cho toàn hệ thống trên kind/k3d | Ngoài phạm vi (D-10) |
| Service mesh / mTLS | Linkerd trên K8s; hoặc mTLS gRPC thủ công (BL-13) | Cần K8s |
| Tách `social-service` | Tách module `social` ra service riêng (BL-12) | Thực hành mẫu "strangler" khi đã có test |
| Consistent hash exchange | Giữ thứ tự theo `channelId` với nhiều consumer | Chỉ cần khi thứ tự trở thành vấn đề thật |
