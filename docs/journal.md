# Nhật ký học (P0-P2)

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
