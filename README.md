# nexus-backend

Backend của Nexus (nền tảng chat kiểu Discord): Java, Spring Boot 4.1, Maven multi-module (microservices). Tài liệu thiết kế nằm trong [docs/](docs/).

## Yêu cầu

- JDK >= 21 (dự án biên dịch với `--release 21`; JDK 25 vẫn build được, image Docker dùng Temurin 21)
- Docker và Docker Compose
- Không cần cài Maven: dùng Maven wrapper `./mvnw`

## Lệnh thường dùng

| Mục đích | Lệnh |
|---------|------|
| Build + test toàn bộ | `./mvnw verify` |
| Kiểm tra format | `./mvnw spotless:check` |
| Format | `./mvnw spotless:apply` |

Thêm module mới: khai báo một dòng `<module>` trong `libs/pom.xml` hoặc `services/pom.xml`. Xem thêm [backend-structure.md](docs/04-project-structure/backend-structure.md#1-build-tool-maven-multi-module-p-03-adr-006).

## Hạ tầng local

| Mục đích | Lệnh |
|---------|------|
| Chuẩn bị lần đầu | `cp .env.example .env && ./infra/scripts/gen-jwt-keys.sh` |
| Chạy Postgres/Redis/RabbitMQ + identity, community, messaging, api-gateway | `docker compose up -d --build --wait` |
| Demo P1 (đăng ký, đăng nhập, refresh, reuse) | `./infra/scripts/demo-p1.sh` |
| Demo P2 (server, invite, join, rời; 6 request auth mỗi lần chạy) | `./infra/scripts/demo-p2.sh` |
| Demo P3 (role Mod, overwrite kênh, ban/gỡ ban; 6 request auth mỗi lần chạy) | `./infra/scripts/demo-p3.sh` |
| Demo P4 (nhắn tin, reaction, phân trang; 6 request auth mỗi lần chạy; thêm `DEMO_STOP_COMMUNITY=1 COMPOSE_PROJECT=<project>` để dừng Community và xem 503) | `./infra/scripts/demo-p4.sh` |
| Thêm Grafana/Loki/Tempo/Prometheus, bật OTLP | `OTEL_ENDPOINT=http://otel-lgtm:4318 docker compose --profile observability up -d --build --wait` |
| Kiểm cách ly schema | `./infra/scripts/verify-isolation.sh` |
| Xóa sạch kể cả dữ liệu | `docker compose down -v` |

Các script demo cần `curl` và `jq` trên máy chạy.

Khóa JWT sinh bởi `gen-jwt-keys.sh` có quyền `644` để container non-root (uid 10001) đọc được bind mount; chỉ dùng cho local, không commit. Mọi cổng publish chỉ bind `127.0.0.1`. Cổng Postgres và Redis trên host mặc định `5432` và `6379`, đổi bằng `POSTGRES_PORT`, `REDIS_PORT` (inline hoặc trong `.env`) nếu bị chiếm.

`COOKIE_SECURE=false` trong `.env` chỉ dùng cho local chạy HTTP (cookie Secure qua HTTP thì curl không gửi lại); đặt `true` khi chạy sau HTTPS. `CORS_ALLOWED_ORIGINS` phải khớp chính xác Origin của trình duyệt.

### Cổng

Chỉ gateway :8080 được publish trong các service ứng dụng; identity, community và messaging chỉ truy cập được qua gateway (trong network compose) và cổng actuator của mọi service không publish.

| Service | Cổng app | Cổng actuator | Publish ra host |
|---------|---------|---------------|-----------------|
| api-gateway | 8080 | 8180 | `127.0.0.1:8080` |
| identity-service | 8081 | 8181 | không |
| community-service | 8082 | 8182 | không |
| messaging-service | 8083 | 8183 | không |

Gateway giới hạn tần suất bằng Redis (NFR-013): `/api/v1/auth/**` khoảng 10 request/phút/IP, API có token khoảng 100 request/phút/user, xem trước invite khoảng 30 request/phút/IP, gửi tin nhắn 5 tin/5 giây/user (bền vững 60/phút); vượt mức trả 429. `infra/scripts/demo-p1.sh` tốn 4 request auth và `demo-p2.sh` và `demo-p3.sh` tốn 6 mỗi lần chạy; hai lần chạy liên tiếp trong 1 phút (6 + 6 > 10, kể cả xen kẽ `demo-p2.sh` và `demo-p3.sh`) sẽ gặp 429, chờ 1 phút rồi chạy lại. Swagger UI gộp ở `http://127.0.0.1:8080/swagger-ui.html`.

## identity-service (P1)

Gọi qua gateway `http://127.0.0.1:8080` (đường dẫn `/api/v1/auth/**`, `/api/v1/users/**`). Biến `CLOUDINARY_*` để trống thì service vẫn chạy nhưng endpoint avatar chưa dùng được.

| Endpoint | Mục đích |
|----------|---------|
| `POST /api/v1/auth/register` | Đăng ký (FR-001) |
| `POST /api/v1/auth/login` | Đăng nhập, trả access token + cookie refresh (FR-002) |
| `POST /api/v1/auth/refresh` | Đổi refresh token (rotation, phát hiện dùng lại) (FR-003) |
| `POST /api/v1/auth/logout` | Đăng xuất (FR-004) |
| `GET /api/v1/users/me`, `PATCH /api/v1/users/me` | Xem, sửa profile (FR-006, FR-007) |
| `POST /api/v1/users/me/avatar/signature`, `PUT /api/v1/users/me/avatar` | Ký upload và lưu avatar qua Cloudinary (FR-008) |
| `GET /api/v1/users/{userId}`, `GET /api/v1/users/by-username/{username}` | Xem profile người khác (FR-010) |
| `GET /api/v1/users?ids=` | Tra cứu hàng loạt profile theo danh sách id, tối đa 100, id trùng được gộp (FR-010) |
| `GET /.well-known/jwks.json` | Khóa công khai để verify JWT |

## community-service (P2)

Gọi qua gateway `http://127.0.0.1:8080`; mọi endpoint cần `Authorization: Bearer <accessToken>` trừ xem trước invite. Từ P3 quyền được kiểm theo permission thật (owner luôn đủ quyền), xem [services.md](docs/02-architecture/services.md#2-community-service) và [permission-model.md](docs/01-requirements/permission-model.md).

| Endpoint | Mục đích |
|----------|---------|
| `POST /api/v1/servers`, `GET /api/v1/servers` | Tạo server (kèm role `@everyone`, category, kênh mặc định), liệt kê server của tôi (FR-020, FR-023) |
| `GET/PATCH/DELETE /api/v1/servers/{serverId}` | Chi tiết, đổi tên, xóa server (FR-021, FR-022) |
| `POST /api/v1/servers/{serverId}/categories`, `PATCH/DELETE .../categories/{categoryId}` | Category (FR-024) |
| `POST /api/v1/servers/{serverId}/channels`, `PATCH/DELETE .../channels/{channelId}` | Kênh (FR-025) |
| `POST/GET /api/v1/servers/{serverId}/invites` | Tạo invite (`maxUses`, `expiresInSeconds`), liệt kê invite (FR-026) |
| `GET /api/v1/invites/{code}` | Xem trước invite, không cần token, 404 nếu mã không có (FR-027) |
| `POST /api/v1/invites/{code}/accept` | Tham gia server, 410 nếu hết lượt, hết hạn hoặc đã thu hồi (FR-028) |
| `DELETE /api/v1/invites/{code}` | Thu hồi invite (FR-029) |
| `DELETE /api/v1/servers/{serverId}/members/@me` | Rời server (FR-030) |
| `GET /api/v1/servers/{serverId}/members?cursor=&limit=` | Danh sách member theo cursor (FR-031) |

### community-service (P3): role, permission, kick, ban

| Endpoint | Mục đích |
|----------|---------|
| `GET/POST /api/v1/servers/{serverId}/roles`, `PATCH/DELETE .../roles/{roleId}` | Liệt kê, tạo, sửa (tên, màu, quyền, vị trí), xóa role theo luật thứ bậc (FR-032) |
| `PUT /api/v1/servers/{serverId}/members/{userId}/roles` | Thay tập role của member `{roleIds}` (FR-033) |
| `PUT/DELETE /api/v1/servers/{serverId}/channels/{channelId}/permissions/{targetType}/{targetId}` | Đặt, gỡ overwrite `{allow, deny}` của role hoặc member (`targetType` = `role` hoặc `member`) (FR-034) |
| `GET /api/v1/servers/{serverId}/permissions/@me` | Quyền hiệu lực của tôi: bitset server và từng kênh nhìn thấy được (FR-034) |
| `DELETE /api/v1/servers/{serverId}/members/{userId}` | Kick member, vào lại được bằng invite (FR-036) |
| `PUT /api/v1/servers/{serverId}/bans/{userId}` | Ban `{reason?}` (tối đa 512 ký tự), idempotent, cả người chưa là member (FR-037) |
| `DELETE /api/v1/servers/{serverId}/bans/{userId}`, `GET .../bans?cursor=&limit=` | Gỡ ban, liệt kê ban (FR-039) |

Bị ban thì `POST /api/v1/invites/{code}/accept` trả `403 BANNED_FROM_SERVER`. Demo: `./infra/scripts/demo-p3.sh` chạy kịch bản `announcements` của [permission-model.md](docs/01-requirements/permission-model.md#7-ví-dụ) rồi ban/gỡ ban qua gateway (17 bước, exit 1 nếu một bước sai mã HTTP).

## api-gateway và observability (P2)

Gateway `:8080` định tuyến `/api/v1/auth/**`, `/api/v1/users/**` tới identity và `/api/v1/servers/**`, `/api/v1/invites/**` tới community và `/api/v1/channels/**`, `/api/v1/attachments/**` tới messaging, xác thực JWT bằng JWKS của identity, gắn `X-Request-Id`, giới hạn tần suất bằng Redis.

Bật observability: `OTEL_ENDPOINT=http://otel-lgtm:4318 docker compose --profile observability up -d --build --wait`, rồi mở Grafana `http://127.0.0.1:3001` (admin/admin) → Explore → Loki, lọc `{service_name="community-service"} | requestId="<X-Request-Id do demo-p2.sh in>"`. Không đặt `OTEL_ENDPOINT` thì exporter tắt và log vẫn ra stdout dạng JSON. Chi tiết ở [observability.md](docs/02-architecture/observability.md).

## messaging-service (P4)

Gọi qua gateway; cổng app 8083, gRPC 9093 (nội bộ), gọi `community-service` gRPC `:9092` để kiểm quyền kênh (deadline 300 ms, retry, circuit breaker; Community chết thì gửi tin trả `503 DEPENDENCY_UNAVAILABLE`). Cache quyền Redis TTL 30 s. Biến `CLOUDINARY_*` để trống thì chữ ký/đính kèm chưa dùng được. Mọi endpoint cần `Authorization: Bearer <accessToken>`.

| Endpoint | Mục đích |
|----------|---------|
| `POST /api/v1/channels/{channelId}/messages` | Gửi tin `{content, nonce?, replyToId?, attachments?}`; cùng `nonce` trả `200` tin cũ (FR-050, FR-053, FR-058, FR-059) |
| `PATCH/DELETE /api/v1/channels/{channelId}/messages/{messageId}` | Sửa (tác giả), xóa mềm (FR-051, FR-052) |
| `GET /api/v1/channels/{channelId}/messages?before=&after=&limit=` | Lịch sử phân trang keyset (FR-055) |
| `PUT/DELETE .../messages/{messageId}/reactions/{emoji}` | Thả, gỡ reaction idempotent (FR-054) |
| `POST /api/v1/attachments/signature` | Chữ ký upload Cloudinary `{channelId}` (FR-101) |

Không có `VIEW_CHANNEL` trả `404`, thiếu quyền khác trả `403`. CI: workflow `messaging-service` build, test, image và Trivy như các service khác; workflow `proto` chạy `buf lint` và `buf breaking` so với `main` khi `proto/**` đổi. Chi tiết gRPC ở [grpc-design.md](docs/02-architecture/grpc-design.md).
