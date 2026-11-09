# Nhật ký học (P0-P4)

- Cách ly schema bằng user DB riêng: mỗi service chỉ thấy schema của mình, `permission denied` ở mức DB mạnh hơn quy ước trong code.
- DDD 4 lớp + DAO: `domain` thuần Java, JPA entity và mapper nằm ở `infrastructure/persistence`; ArchUnit giữ ranh giới này.
- Refresh token rotation: mỗi lần refresh phát token mới và thu hồi token cũ; dùng lại token cũ thì thu hồi cả family và trả `REFRESH_TOKEN_REUSED`.
- Race condition khi đăng ký: kiểm tra trùng trước rồi insert không đủ, phải dựa unique constraint và bắt lỗi vi phạm để trả `409`.
- Docker layer cache: copy POM trước source, `dependency:go-offline` thành layer riêng; sửa code chỉ build lại layer `application` (layer dependencies báo `CACHED`).
- Aggregate nhỏ (`Server`, `Category`, `Channel`, `Member`, `Invite` tách riêng): member join không khóa `Server`, mỗi aggregate chỉ giữ bất biến của nó và hai request ghi khác aggregate không tranh chấp.
- Join đồng thời cần câu lệnh nguyên tử, không đọc rồi ghi: `INSERT ... ON CONFLICT DO NOTHING` cho member và `UPDATE ... SET uses = uses + 1 WHERE uses < max_uses` cho invite, chạy trong một transaction; IT 20 thread với `maxUses=5` luôn ra đúng 5 member mới.
- Xác thực hai lớp: gateway verify JWT bằng JWKS để chặn sớm, community-service vẫn verify lại bằng JWKS của chính nó vì không được tin mạng nội bộ; cả hai chỉ cần khóa công khai, không gọi identity mỗi request.
- Token bucket của Redis tính theo giây: 10 request/phút = `replenishRate=1`, `requestedTokens=6`, `burstCapacity=60` (60/6 = 10 request burst, 1*60/6 = 10 request/phút bền vững). Vì vậy `demo-p2.sh` (6 request auth) chạy hai lần trong 1 phút sẽ 429.
- OTLP với Boot 4.1: `spring-boot-starter-opentelemetry` lo trace và metric; log phải tự thêm `opentelemetry-logback-appender-1.0` (alpha) vì Boot không bắc cầu Logback sang `SdkLoggerProvider`. Có `logback-spring.xml` riêng thì với profile không phải `local` phải include `structured-console-appender.xml`, không thì log mất định dạng JSON (profile `local` dùng `console-appender.xml` in text thường). Env `OTEL_EXPORTER_OTLP_ENDPOINT` rỗng vẫn bị Boot nối thành `/v1/metrics` nên phải tự tắt ba cờ export (`OtelExportEnvironmentPostProcessor`).
- JWKS cache của gateway chưa gộp request: mỗi lần hết cooldown có thể có nhiều tải JWKS song song, và khi identity chết thì mỗi request mang kid lạ lại thử tải (bị chặn bởi connect/response timeout). Muốn gộp thì dùng `Mono.cache` hoặc khóa single-flight.
- Mã invite là thông tin tham gia: log không được chứa nó, `PathMask` đổi `/invites/{mã}` thành `/invites/{code}` ở mọi chỗ ghi path (cả gateway lẫn servlet).
- Spring Cloud 2025.1.4 chạy trên Boot 4.1.2: release train này dựng cho Boot 4.0, verifier chấp nhận 4.1.x và mọi IT của gateway xanh; xem lại khi 2026.0 GA.
- Domain service thuần: `PermissionCalculator` và `RoleHierarchyPolicy` không phụ thuộc Spring, nên test dạng bảng (mỗi dòng một kịch bản vào/ra) bao phủ từng bước thuật toán mà không cần DB; gRPC `PermissionService` ở P4 chỉ gọi lại calculator.
- Gán role dùng hiệu đối xứng giữa tập role cũ và mới để biết role nào thêm, role nào bớt rồi chỉ kiểm thứ bậc trên các role đó; đọc rồi ghi `member_roles` mà không khóa thì hai lần gán đồng thời ghi đè nhau (lost update), nên khóa hàng member bằng `SELECT ... FOR UPDATE` trước khi tính.
- Đổi chỗ `position` của role vi phạm unique `(server_id, position)` ở giữa chừng, nên constraint phải `DEFERRABLE INITIALLY DEFERRED` để chỉ kiểm khi commit.
- Race ban × join: `SELECT` kiểm ban rồi `INSERT` member không đủ vì ban có thể chen vào giữa hai câu lệnh; `pg_advisory_xact_lock` theo cặp `(server, user)` ở cả join, ban, gỡ ban và kick tuần tự hóa chúng. Bỏ lời gọi khóa ở `AcceptInviteUseCase` thì `BanJoinConcurrencyIT` fail ngay vòng đầu (member bị ban); IT dùng nhiều accept song song mỗi vòng để cửa sổ race đủ rộng.
- Ban là audit tối thiểu bằng chính dữ liệu nghiệp vụ: dòng `server_bans` đã lưu `bannedBy`, `reason`, `createdAt` nên không cần bảng audit riêng.

## P4 Messaging + gRPC

- gRPC cần deadline ở mỗi call, retry chỉ cho `UNAVAILABLE` của RPC đọc, và circuit breaker theo callee; kiểm tra quyền **fail-closed**: Community chết thì gửi tin trả `503 DEPENDENCY_UNAVAILABLE`, không cho qua.
- Cache quyền theo cache-aside (`perm:{channelId}:{userId}`, TTL 30 s) nhưng **fail-open** ở phía cache: Redis lỗi thì coi như miss và gọi gRPC, không làm sập luồng gửi tin.
- Boot 4.1 tự lo gRPC server observation và có interceptor OAuth2 riêng, nên `nexus-common-grpc` phải chặn interceptor trùng và không thêm observation server thứ hai; client thì phải tự gắn `ObservationGrpcClientInterceptor`.
- Race `nonce`: unique `(author_id, nonce)` quyết định bên thắng, bên thua bắt `DataIntegrityViolationException` và chạy lại **ngoài** transaction đã hỏng để đọc tin đã có.
- Keyset + `EXPLAIN`: khi một kênh chiếm ~50% bảng, planner chọn pkey (quét ngược) thay vì index `(channel_id, id DESC)`; IT dữ liệu dùng 100 kênh x 2000 tin để plan phản ánh đúng thực tế.

### Rủi ro đã biết (P4)

- Polling `after=` có thể lỡ tin commit trễ (id sinh trước, commit sau); P6 realtime xử lý.
- Cache quyền cũ tối đa 30 s sau kick/ban/đổi role cho tới khi P5 xóa cache theo event.
- Thứ tự `mentionUserIds` khi đọc lại không được đảm bảo (đọc theo `userId`, `message_mentions` không có cột thứ tự).
- Xóa mềm xóa dòng attachment nên file Cloudinary thành mồ côi.
- Gọi HTTP tới Cloudinary nằm trong transaction gửi tin (tối đa 5 file x 5 s giữ một kết nối DB).

### Kết quả tắt Community

Tắt Community, gửi 1 + 20 tin: cả 21 lần 503 `DEPENDENCY_UNAVAILABLE`; lần gửi đầu và 16 lần tiếp theo (17 lần) chậm 0,15 đến 0,18 s (gọi gRPC thất bại rồi retry), 4 lần cuối trong chuỗi 21 lần còn 0,017 đến 0,022 s (breaker `OPEN`, không gọi gRPC). Chưa xem `CLOSED → OPEN → HALF_OPEN` trên Grafana (`resilience4j_circuitbreaker_state`), bạn tự xem; bật lại Community và chờ ~12 s thì gửi lại được 201 (0,29 s).

### Kết quả demo, Tempo và breaker

Chạy thật bằng `docker compose -p nexus-p4demo --profile observability` (project cô lập, `POSTGRES_PORT=55432`), lần đầu có messaging-service trong stack.

- **Lỗi tích hợp phát hiện ở lần chạy đầu**: gửi tin nào cũng trả `503 DEPENDENCY_UNAVAILABLE` dù Community đang chạy. Nguyên nhân: `ManagedChannelBuilder.forTarget(target)` thiếu `usePlaintext()`, client nói TLS còn server Community nghe plaintext (log Community: `HTTP/2 client preface string missing or corrupt`, byte đầu `16030301` là TLS ClientHello). Đã sửa bằng `.usePlaintext()` (TLS/mTLS ở backlog BL-13) và test `CommunityChannelPlaintextTest` dựng kênh qua `CommunityChannelConfig.newChannel` tới server Netty plaintext thật; test đỏ với code cũ (`DependencyUnavailableException: community call failed`), xanh sau khi sửa.
- **Bài học**: unit/IT in-process (`InProcessChannelBuilder`) không bắt được lỗi TLS/plaintext vì không đi qua transport thật; cần ít nhất một test qua Netty thật hoặc chạy compose.
- **Demo trên HEAD đã sửa** (`demo-p4.sh`): đủ 13 bước nhắn tin đạt (201; 200 cùng id khi trùng nonce; reply; reaction 204 và lịch sử thấy 👍 count=1 me=true; sửa có `editedAt`; `after=` tăng dần; người ngoài 404; xóa 204; tombstone `deleted=true, content=null`). Phần 503: `FLUSHALL` redis, dừng Community, lần gửi đầu 503 sau 0,159 s (< 1 s), 20 lần tiếp theo như bài 3.
- **Cold-start**: kênh gRPC gọi `getState(true)` ngay khi dựng để kết nối sớm. Gửi tin đầu tiên ngay sau khi stack khởi động: 201, 1075 ms phía messaging (gồm khởi tạo lạnh, không riêng gRPC); restart riêng messaging-service rồi gửi ngay: 201, 1100 ms. Chỉ 2 mẫu, chưa thấy 503 lúc lạnh. Lần chạy trước khi sửa từng có một lần gửi đầu 503 sau 0,84 s sau khi bật observability; chưa rõ nguyên nhân (nghi deadline 300 ms bị vượt khi gRPC chạy lạnh), nên coi là rủi ro đã biết: deadline giữ 300 ms theo spec, không nới.
- **Tempo** (trên HEAD đã sửa): trace `63c371912a4064322ec9ca84ef9259d1` của lần gửi tin bước 4 có span của `api-gateway`, `messaging-service` và `community-service`; span `PermissionService/CheckChannelPermissions` CLIENT (messaging) là cha của span SERVER (community). Đạt.
- Bài 1, 2, 4, 5: chưa làm, bạn tự chạy. Gợi ý: (1) `grpcurl -plaintext -H "authorization: Bearer <jwt>" localhost:9092 nexus.community.v1.PermissionService/CheckChannelPermissions` (cổng 9092 chưa publish ra host, thêm tạm vào compose hoặc chạy từ container); (2) thêm tạm `Thread.sleep(500)` vào `PermissionGrpcService`; (4) mở PR thử đổi số field trong `proto/`; (5) `docker compose up -d --scale community-service=2`.
- Lưu ý CI (dự kiến, workflow chưa chạy trên GitHub): `proto.yml` kiểm base của PR có `proto/` không và bỏ qua `buf breaking` khi chưa có (PR đầu tiên thêm `proto/`); nhãn `buf skip breaking` vẫn bỏ qua được breaking, nhưng gắn nhãn xong phải push thêm một commit mới thì job mới chạy lại với nhãn.

## P5 Message queue

- **Outbox + `FOR UPDATE SKIP LOCKED`**: service ghi event vào `outbox_event` cùng transaction với nghiệp vụ, relay (batch 100, mỗi 500 ms) khóa các dòng chưa gửi bằng `SKIP LOCKED` nên nhiều instance relay không giẫm nhau; publish theo thứ tự `id`, một dòng lỗi thì dừng batch để giữ thứ tự. Broker chết không làm request lỗi, chỉ làm dòng outbox chờ.
- **Confirm khác ack**: publisher confirm (`publisher-confirm-type: correlated`, `simple` không bật confirm) cho relay biết broker đã nhận và route, rồi mới điền `published_at`; ack là việc của consumer báo broker đã xử lý xong. Confirm quá 5 s hoặc crash giữa chừng thì gửi lại, nên giao nhận là at-least-once, trùng được loại ở inbox.
- **Inbox cùng transaction**: `InboxGuard` ghi `processed_event` và áp nghiệp vụ trong một transaction, ack sau commit. Event trùng `eventId` bị bỏ vì khóa chính; rollback thì cả hai cùng mất và broker giao lại.
- **Retry phân loại lỗi**: lỗi parse, `IllegalArgumentException`, `AmqpRejectAndDontRequeueException` vào DLQ ngay vì thử lại không đổi kết quả; lỗi còn lại thử 3 lần tổng cộng (cách nhau 1 s, 2 s) rồi reject không requeue vào `<queue>.dlq`. Nhờ vậy một message hỏng không chặn queue và lỗi tạm thời (DB, Redis) vẫn tự lành.
- **Version nhúng trong value cache**: cache quyền giữ khóa `perm:{channelId}:{userId}`, value `serverId|bits|ver`; mỗi lần đọc so `ver` với `perm:ver:{serverId}`, lệch là miss. Event đổi quyền chỉ cần `INCR` một khóa theo server thay vì xóa từng key. Cửa sổ stale còn lại: từ lúc đổi quyền ở Community tới lúc messaging xử lý event (độ trễ queue) và request chen giữa `INCR` với commit; TTL 60 s chặn phần rò còn lại.
- **`ChannelDeleted` tới trước `ChannelCreated`**: consumer ghi tombstone (`channel_refs.deleted_at`) kể cả khi chưa có dòng kênh, nên `ChannelCreated` đến sau không làm kênh sống lại và gửi tin vào kênh đó vẫn 404.

### Khác biệt code và event-catalog (phát hiện bởi contract test)

- Envelope không có `aggregateVersion` (bảng outbox không có cột) và không có `traceparent` trong body: trace đi bằng header AMQP `traceparent`. Catalog đã sửa.
- Đường dẫn mẫu là `contracts/events/<EventType>.json` (chỉ `payload`), không phải `<producer>/<EventType>.v<n>.json`. Catalog §9 đã sửa.
- `MessageCreated/Edited/Deleted.serverId` luôn có giá trị trong code (null sẽ NPE), catalog cũ ghi `serverId?`; đã bỏ dấu `?`. DM chưa phát các event này.
- Tên khóa của cả 26 event khớp catalog; hai bài thử đổi tên khóa (`kickedBy`, `publicId`) làm đúng một contract test đỏ.
- `identity.events` chỉ xuất hiện trên broker sau lần relay publish đầu tiên (identity không có listener nên không mở kết nối lúc khởi động); `community.events` và `messaging.events` có ngay vì messaging khai báo.

### Rủi ro đã biết (P5)

- Dòng outbox lỗi vĩnh viễn (payload không serialize được, exchange bị xóa) chặn relay của service đó: chưa có ngưỡng bỏ qua, `attempts` chỉ tăng; chỉ gauge `nexus.outbox.pending` cho thấy.
- Event không có queue nào bind routing key đó bị broker bỏ (publish thành công nhưng không route), không báo lỗi.
- Confirm timeout hoặc crash sau khi broker đã nhận có thể publish hai lần (at-least-once); inbox ở consumer loại bản trùng, nhưng consumer ngoài repo phải tự idempotent.
- Cả 3 service dùng chung user admin RabbitMQ; user riêng theo `security.md` làm ở P9.
- Cửa sổ stale quyền còn nhỏ nhưng có thật (xem trên); mỗi event community được xử lý bump `perm:ver` hai lần có chủ đích (một lần trong transaction để Redis lỗi thì rollback và giao lại, một lần sau commit để cache không giữ version cũ), vô hại.
- Dashboard Grafana và queue depth hoãn sang P9; chỉ có gauge `nexus.outbox.pending`.
- Retry là 3 lần tổng cộng (cách nhau 1 s, 2 s), không phải 4 như dãy 1 s, 2 s, 4 s ghi ở spec cũ.
- `spring.rabbitmq.publisher-confirm-type` phải là `correlated`; để `simple` thì không có confirm và relay đánh dấu đã gửi mà chưa chắc broker nhận.
- Chưa kiểm trace REST, outbox, consume trong Tempo (không bật profile observability ở lần đóng phase này).

### Kết quả demo P5

`infra/scripts/demo-p5.sh` chạy trên project compose cô lập (`docker compose -p nexus-p5demo`, `POSTGRES_PORT=55432`, `REDIS_PORT=56379`), exit 0, 3 dòng `PASS`:

1. Tắt messaging-service, tạo 3 kênh (201), queue `messaging.community.events` giữ ít nhất 3 message; bật lại, `messaging.channel_refs` đủ 3 dòng. PASS.
2. Publish payload hỏng (`{khong-phai-json`) vào `community.events` routing key `channel.deleted`: `messaging.community.events.dlq` tăng đúng 1 message, queue chính về 0. Log messaging-service chỉ có một lần `Execution of Rabbit message listener failed` rồi `Retries exhausted ... RejectAndDontRequeueRecoverer`: lỗi parse không đáng retry nên vào DLQ ngay, không có 3 lần retry (đúng thiết kế; 3 lần retry chỉ thấy với lỗi tạm thời, ví dụ DB lỗi). Bước "sửa code rồi move message về queue gốc" chưa làm. PASS.
3. Dừng rabbitmq, tạo server vẫn 201, dòng `ServerCreated` trong `community.outbox_event` có `published_at` NULL; bật lại thì `published_at` được điền. PASS.

Kiểm tay thêm: Management API thấy exchange `identity.events`, `community.events`, `messaging.events` (type topic) và có `publish_in` > 0 sau khi tạo user, server, kênh, tin; nhắn tin sinh message trên `messaging.events`. Tạo 20 kênh trong lúc `docker compose stop messaging-service` rồi bật lại: `channel_refs` đủ 21/21 (1 kênh mặc định + 20), không mất event (chưa chắc lệnh stop trùng đúng lúc đang xử lý vì 20 request chạy rất nhanh; kịch bản mất message khi đang xử lý được `GracefulShutdownIT` phủ).

## P6 Realtime + Presence

- **Gateway stateful, service stateless**: realtime-gateway giữ socket và sổ subscription trong bộ nhớ nên mỗi replica là một thực thể riêng (queue riêng, id theo `HOSTNAME`); presence-service không giữ gì ngoài Redis nên chạy bao nhiêu bản cũng được.
- **Fan-out bằng queue riêng mỗi instance**: mỗi replica có queue exclusive auto-delete `realtime-gateway.<instanceId>` bind mọi exchange; broker nhân bản event vào cả hai, replica nào không có subscriber thì bỏ. Demo thấy đúng 2 queue `realtime-gateway.*` trên Management API.
- **Lua nguyên tử cho session**: connect/disconnect/purge là script Lua một round trip nên không có trạng thái nửa vời; tombstone `presence:closed:{sid}` 60 s chặn `SessionConnected` đến muộn sau `SessionDisconnected`.
- **Heartbeat phát hiện instance chết**: `kill -9` không phát `SessionDisconnected`, chỉ khóa `presence:instance:{i}:alive` (TTL 60 s, heartbeat 30 s) hết hạn; job dọn mỗi 15 s thấy mất `alive` rồi purge session của nó, nên cận trên kill tới OFFLINE là 60 + 15 = 75 s. (Ban đầu để 90 s + 30 s thì khoảng 60 đến 120 s, đo được 66 s chỉ là may; đã chỉnh để thiết kế đạt tiêu chí 90 s.) Heartbeat mang `sessionIds[]` và `userIds[]` song song để nối lại session mất event.
- **Tạm dừng trước khi hỏi lại quyền**: re-check là RPC tuần tự nên nếu chỉ xóa sổ sau khi có kết quả thì subscription kiểm sau cùng vẫn nhận frame suốt cả lô. `SubscriptionRegistry` giữ tập tạm dừng theo đúng giá trị `SessionSubscription`, `RevocationService` tạm dừng cả lô trước RPC đầu tiên rồi mở lại từng cái được phép hoặc thu hồi; mọi đường xóa đều dọn tạm dừng nên subscription thay thế cùng id không bị ảnh hưởng.
- **Riêng tư ở tầng subscription**: không có topic presence chung; `PresenceChanged` chỉ vào `/topic/servers/{id}` mà người nghe đã qua `CheckServerMembership`, thu hồi bằng sổ subscription cộng bộ lọc outbound (frame chỉ ra khỏi socket nếu cặp session-subscription còn trong sổ). Service dùng `x-service-token` cho các lần kiểm tra sau CONNECT vì JWT user có thể đã hết hạn.

Khác biệt đáng nhớ: Spring simple broker không trả frame `RECEIPT`, nên client chỉ biết SUBSCRIBE thành công bằng cách không nhận `ERROR` frame; script demo chờ 3 s không lỗi mới coi là sẵn sàng. Reactor Netty mặc định cắt dòng đầu request ở 4096 byte nên gateway trả `414` cho `GET /presence?userIds=` 200 UUID (khoảng 7,5 KB); `server.netty.max-initial-line-length: 16KB` sửa được, `GatewayWebSocketIT` đỏ `414` khi bỏ dòng này.

### Rủi ro đã biết (P6)

- Đua nạp projection × event đã được rào bằng phiên bản: mỗi event áp vào user tăng `presence:rel:{u}:rev` (và `ServerDeleted` lấy members, xóa tập members và tăng `presence:rel:epoch` trong một Lua nguyên tử, load nào đã cài trước đó nằm trong tập members trả về để gỡ server), `load` chỉ ghi khi phiên bản đọc trước khi lấy snapshot còn nguyên; lệch thì đọc lại và thử tối đa 3 lần, quá 3 lần ném `RelationUnavailableException` (fail-closed, người gọi coi là không thấy). Rủi ro còn lại: user có event dồn dập liên tục có thể không bao giờ nạp được trong lúc đó.
- Trong lúc re-check quyền (RPC đồng bộ tuần tự, tối đa 500 ms mỗi lần), subscription bị ảnh hưởng nằm trong tập tạm dừng nên frame bị bộ lọc outbound bỏ; chỉ mở lại sau khi kiểm quyền cho phép. Hệ quả: người vẫn có quyền có thể mất frame trong cả lô lúc chờ RPC (không bù lại, client lấy lại qua REST).
- `PresenceChanged` và `Session*` publish đồng bộ best-effort, không outbox: broker treo thì chặn thread STOMP tới timeout (`connection-timeout 2s`, `channel-rpc-timeout 5s`); event mất được bù ở heartbeat kế tiếp hoặc lần nạp lại.
- Heartbeat mang toàn bộ `sessionIds[]` + `userIds[]` của instance, không giới hạn kích thước (hàng chục nghìn session sẽ thành message lớn).
- Script Lua dùng nhiều key khác slot nên không an toàn với Redis Cluster; chỉ chạy được trên Redis đơn.
- Hạn JWT chỉ kiểm lúc CONNECT; socket đang mở giữ quyền sau khi token hết hạn cho tới khi đóng (chỉ bị thu hồi khi quyền đổi).
- Còn các cửa sổ TOCTOU nhỏ trong sổ subscription (kiểm quyền xong, đăng ký sổ, event thu hồi đến giữa hai bước).
- Instance id lấy từ `HOSTNAME` (id container): `kill -9` rồi restart cùng container giữ id cũ, queue exclusive cũ còn khóa tới khi broker phát hiện connection chết nên instance mới có thể không khai báo được queue ngay.
- Tập `presence:server:{id}:members` (mirror để xử lý `ServerDeleted`) tăng không giới hạn cho user chưa bao giờ được nạp.
- Chưa có bạn bè và DM: `friendIds` luôn rỗng, kiểm quyền kênh DM thuộc P7.
- Lần SUBSCRIBE đầu tiên ngay sau khi stack khởi động từng quá hạn gRPC 300/500 ms (kênh lazy, lạnh) và bị từ chối fail-closed với `DEPENDENCY_UNAVAILABLE`; đã sửa bằng kết nối chủ động (`getState(true)`) ở cả realtime-gateway lẫn presence-service và `depends_on` community/messaging khỏe trong compose, nhưng kênh vẫn có thể rớt rồi nối lại trong lúc chạy.
- Event `Session*` publish trên nhiều channel AMQP nên có thể đến presence-service sai thứ tự giữa hai session khác nhau của cùng user (IT `twoDevicesOfflineOnlyWhenLastCloses` từng flaky vì `OFFLINE` rồi `ONLINE`); tombstone chỉ che trường hợp disconnect đến trước chính connect của nó, nên user có thể chớp `OFFLINE` ngắn rồi `ONLINE` lại.
- Server không backfill khi client reconnect: tin lỡ trong lúc mất kết nối không được đẩy lại, client phải gọi REST (`GET .../messages?after=`) vì REST là nguồn sự thật.
- Socket dài hạn qua api-gateway: `GatewayWebSocketIT` đặt response-timeout 2 s, để socket nhàn rỗi 4 s vẫn sống và còn nhận echo, nên timeout phản hồi 30 s của gateway không cắt WebSocket; chưa chạy phiên dài hàng giờ.
- Token service dùng chung env, một giá trị mỗi caller, chưa có mTLS (BL-13); RabbitMQ vẫn dùng chung user admin.
- Chưa kiểm trace Tempo (không bật profile observability ở lần đóng phase này); chưa mở `infra/demo/realtime-demo.html` bằng trình duyệt thật (môi trường chạy không có trình duyệt).

### Kết quả demo P6

`infra/scripts/demo-p6.sh` chạy trên project cô lập (`docker compose -p nexus-p6demo`, `POSTGRES_PORT=55432`, `REDIS_PORT=56379`, token gRPC sinh ngẫu nhiên trong env), exit 0, 4 dòng `PASS`:

1. RabbitMQ Management API có đúng 2 queue `realtime-gateway.*` (2 replica). PASS.
2. Client STOMP (container `node:22-alpine`, `@stomp/stompjs@7.1.1` + `ws@8.18.3`) kết nối qua api-gateway `:8080/ws`, subscribe kênh, server, `/user/queue/events`; user kia POST tin (201) thì client nhận `MessageCreated`. PASS.
3. Người chung server thấy B `ONLINE`; người lạ nhận `[]`. PASS.
4. `kill -9` replica đang giữ session của B: B về `OFFLINE` trong ngưỡng 90 s. PASS.

Sau vòng sửa 1 (alive-ttl 60 s, job 15 s, kênh gRPC kết nối chủ động) chạy lại 5 lần bước 4, mỗi lần kill một replica đang giữ session: 65 s, 43 s, 64 s, 45 s, 56 s (cận trên thiết kế 75 s; đã `down -v` và dựng lại sạch cho ba lần cuối, mỗi lần đủ 4 PASS và không còn lỗi `DEPENDENCY_UNAVAILABLE`/thử lại ở bước 2). Một lần chạy FAIL ở bước 3 là do tôi đổi giá trị `GRPC_TOKEN_*` giữa hai lần chạy nên compose tạo lại community/messaging với token mới còn presence giữ token cũ (`community call failed`), không phải lỗi script; script bật lại replica bằng `up -d --no-deps` để không đụng service khác. Số đo ban đầu 66 s (alive-ttl 90 s, job 30 s) là mẫu may mắn vì thiết kế cũ cho 60 đến 120 s.

Script sau khi kill tự bật lại replica ở EXIT trap nên chạy lại liền được. `down -v` xong, không còn container, network, volume nào của `nexus-p6demo`.

## Bằng chứng observability (Task 13)

Chạy `OTEL_ENDPOINT=http://otel-lgtm:4318 docker compose --profile observability up -d --build --wait` rồi `infra/scripts/demo-p2.sh` (exit 0, 9 bước). Truy vấn qua Grafana `http://127.0.0.1:3001` (admin/admin), datasource proxy `/api/datasources/proxy/uid/loki/loki/api/v1/query_range`; label `service_name` có 3 giá trị `api-gateway`, `community-service`, `identity-service`.

| Bước (requestId) | Truy vấn Loki | Kết quả |
|------------------|---------------|---------|
| login A (`c88fbfa6-...`) | `{service_name="api-gateway"} \|= "requestId=c88fbfa6-..."` | 1 dòng gateway: `POST /api/v1/auth/login status=200` |
| | `{service_name="identity-service"} \| requestId="c88fbfa6-..."` | 1 dòng identity: `POST /api/v1/auth/login status=200` |
| A tạo server (`63c715ed-...`) | `{service_name="api-gateway"} \|= "requestId=63c715ed-..."` | 1 dòng gateway: `POST /api/v1/servers status=201` |
| | `{service_name="community-service"} \| requestId="63c715ed-..."` | 1 dòng community: `POST /api/v1/servers status=201` |
| cùng trace của bước tạo server (`traceId=ff2bcf46833759729884b2e9e1964a0a`) | `{service_name=~".+"} \| traceId="ff2bcf46..."` | 2 dòng: api-gateway và community-service |

Mỗi request chỉ có 2 thành phần (gateway + service đích) vì chưa có service gọi service; mục tiêu 3 thành phần của backend-plan P2 #12 chờ P4. `requestId` của gateway nằm trong nội dung dòng log (reactive không có MDC) nên lọc bằng `|=`, còn của identity/community là structured metadata nên lọc bằng `| requestId="..."`.

Tempo (`/api/datasources/proxy/uid/tempo/api/traces/ff2bcf46833759729884b2e9e1964a0a`): một trace gồm span `http post` (api-gateway, gốc) → `HTTP POST` (client của gateway) → `http post /api/v1/servers` (community-service, parent chính là span client của gateway), tức traceparent đi xuyên gateway sang service. Prometheus có `http_server_requests_milliseconds_count` và `jvm_memory_used_bytes` cho cả 3 service.

## Việc còn lại của người dùng

- Điền `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` trong `.env`.
- Tạo 2 upload preset chế độ Signed trên Cloudinary: `nexus_avatar` và `nexus_attachment`.
- Chạy thủ công kiểm tra upload avatar thật (Step 5 của Task 14, đã bỏ qua vì cần key thật): xin chữ ký, upload lên Cloudinary, `PUT /users/me/avatar`; ghi kết quả vào đây: _chưa chạy_.
- Push nhánh, mở PR, xác nhận CI xanh và image `ghcr.io/quocanh112233/nexus-identity-service` xuất hiện trên GHCR.
- Image identity-service nặng ~270MB filesystem (415MB theo `docker images`), vượt mục tiêu <250MB; chấp nhận vì base bắt buộc `eclipse-temurin:21-jre-alpine` đã ~212MB, jlink/JRE tùy biến hoãn sang P9.
- Push nhánh `feat/p2-community`, mở PR, xác nhận CI xanh và các image `ghcr.io/quocanh112233/nexus-community-service`, `ghcr.io/quocanh112233/nexus-api-gateway` xuất hiện trên GHCR; sau đó tự tạo tag `v0.2.0`.
- P5: sau khi merge, tự tạo tag `v0.5.0`; tự chạy bài message queue 2, 4, 6 và xem message rate trên RabbitMQ UI (`http://127.0.0.1:15672`).
- P6: sau khi merge, tự tạo tag `v0.6.0`; mở `infra/demo/realtime-demo.html` bằng 2 trình duyệt (serve từ `http://localhost:3000`) để xem chat realtime, typing và presence bằng mắt; kiểm trace Tempo cho luồng WebSocket nếu cần.
