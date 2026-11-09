# Cấu trúc hạ tầng (Docker Compose)

File này mô tả `compose.yaml`, init script Postgres, RabbitMQ, biến môi trường và quản lý secret để chạy toàn hệ thống bằng **một lệnh**. Quyết định: [ADR-007](../00-overview/adr/ADR-007-docker-packaging.md).

## 1. File compose

| File | Nội dung |
|------|---------|
| `compose.yaml` (gốc `nexus-backend`) | `include: [infra/compose.infra.yaml, infra/compose.app.yaml]`, `name: nexus` |
| `infra/compose.infra.yaml` | `postgres`, `redis`, `rabbitmq`, `otel-lgtm` (`profiles: [observability]`) |
| `infra/compose.app.yaml` | `identity-service`, `community-service`, `messaging-service`, `presence-service`, `notification-service`, `api-gateway`, `realtime-gateway`, `frontend` |
| `infra/compose.override.example.yaml` | Mở cổng gRPC/actuator ra host để debug; copy thành `compose.override.yaml` (git-ignore) |

| Lệnh | Tác dụng |
|------|---------|
| `cp .env.example .env && ./infra/scripts/gen-jwt-keys.sh` | Chuẩn bị lần đầu |
| `docker compose up -d --build` | **Chạy toàn bộ hệ thống** |
| `docker compose --profile observability up -d` | Thêm Grafana/Loki/Tempo/Prometheus |
| `docker compose up -d postgres redis rabbitmq` | Chỉ hạ tầng (chạy service bằng IDE) |
| `docker compose up -d --scale realtime-gateway=2 --scale messaging-service=2` | Thí nghiệm scale (cần bỏ `container_name`, gateway route theo DNS) |
| `docker compose down -v` | Xóa sạch kể cả dữ liệu |

Frontend trong compose: `image: ghcr.io/quocanh112233/nexus-frontend:${FRONTEND_TAG:-latest}` + `build.context: ${FRONTEND_DIR:-../../frontend}` (clone `nexus-frontend` cạnh `nexus-backend`). Không có repo frontend → dùng image GHCR.

## 2. Mẫu dịch vụ (rút gọn)

```yaml
# infra/compose.infra.yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${POSTGRES_ADMIN_PASSWORD}
      POSTGRES_DB: nexus
      IDENTITY_DB_PASSWORD: ${IDENTITY_DB_PASSWORD}
      COMMUNITY_DB_PASSWORD: ${COMMUNITY_DB_PASSWORD}
      MESSAGING_DB_PASSWORD: ${MESSAGING_DB_PASSWORD}
      NOTIFICATION_DB_PASSWORD: ${NOTIFICATION_DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./postgres/init:/docker-entrypoint-initdb.d:ro
    ports: ["127.0.0.1:${POSTGRES_PORT:-5432}:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d nexus"]
      interval: 5s
      retries: 10
  rabbitmq:
    image: rabbitmq:4-management-alpine
    volumes:
      - ./rabbitmq/rabbitmq.conf:/etc/rabbitmq/rabbitmq.conf:ro
      - ./rabbitmq/definitions.json:/etc/rabbitmq/definitions.json:ro
      - ./rabbitmq/enabled_plugins:/etc/rabbitmq/enabled_plugins:ro
    ports: ["127.0.0.1:15672:15672"]
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      retries: 10
  redis:
    image: redis:7-alpine
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
  otel-lgtm:
    image: grafana/otel-lgtm:0.39.0
    profiles: ["observability"]
    ports: ["127.0.0.1:3001:3000", "127.0.0.1:4317:4317", "127.0.0.1:4318:4318"]
volumes:
  pgdata:
```

```yaml
# infra/compose.app.yaml (một service mẫu)
services:
  messaging-service:
    image: ghcr.io/quocanh112233/nexus-messaging-service:${TAG:-latest}
    build:
      context: ..
      dockerfile: services/messaging-service/Dockerfile
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DB_URL: jdbc:postgresql://postgres:5432/nexus?currentSchema=messaging
      DB_USER: messaging_svc
      DB_PASSWORD: ${MESSAGING_DB_PASSWORD}
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USER: messaging_svc
      RABBITMQ_PASSWORD: ${MESSAGING_MQ_PASSWORD}
      REDIS_HOST: redis
      COMMUNITY_GRPC_TARGET: dns:///community-service:9092
      IDENTITY_GRPC_TARGET: dns:///identity-service:9091
      JWKS_URI: http://identity-service:8081/.well-known/jwks.json
      CLOUDINARY_CLOUD_NAME: ${CLOUDINARY_CLOUD_NAME}
      CLOUDINARY_API_KEY: ${CLOUDINARY_API_KEY}
      CLOUDINARY_API_SECRET: ${CLOUDINARY_API_SECRET}
      CLOUDINARY_UPLOAD_PRESET: ${CLOUDINARY_UPLOAD_PRESET_ATTACHMENT}
      CLOUDINARY_FOLDER_ROOT: ${CLOUDINARY_FOLDER_ROOT:-nexus-dev}
      OTEL_EXPORTER_OTLP_ENDPOINT: ${OTEL_ENDPOINT:-}
    depends_on:
      postgres: { condition: service_healthy }
      rabbitmq: { condition: service_healthy }
      redis: { condition: service_healthy }
      community-service: { condition: service_healthy }
    deploy:
      resources:
        limits: { memory: 512m }
```

Trong compose hiện tại chỉ `api-gateway` publish `127.0.0.1:8080`; identity và community không publish cổng ra host, gateway phụ thuộc identity, community và redis (`service_healthy`).

Thứ tự khởi động nhờ `depends_on: condition: service_healthy`: hạ tầng → identity → community → messaging → presence/notification → gateways → frontend. Nhưng service **vẫn phải tự chịu được** phụ thuộc chết sau khi đã chạy (retry kết nối, circuit breaker) — `depends_on` chỉ giúp lúc khởi động.

## 3. Init script Postgres

`infra/postgres/init/01-create-schemas-and-users.sh` (chạy một lần khi volume trống):

```bash
#!/bin/bash
set -euo pipefail
# Tạo schema $1 thuộc user $2 (mật khẩu $3) và chặn mọi user khác truy cập
create() {
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<SQL
CREATE USER $2 WITH PASSWORD '$3';
CREATE SCHEMA $1 AUTHORIZATION $2;
REVOKE ALL ON SCHEMA $1 FROM PUBLIC;
ALTER ROLE $2 SET search_path = $1;
SQL
}
psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "REVOKE CREATE ON SCHEMA public FROM PUBLIC;"
create identity      identity_svc      "$IDENTITY_DB_PASSWORD"
create community     community_svc     "$COMMUNITY_DB_PASSWORD"
create messaging     messaging_svc     "$MESSAGING_DB_PASSWORD"
create notification  notification_svc  "$NOTIFICATION_DB_PASSWORD"
```

Kết quả: `messaging_svc` không đọc được `community.*` (thử `SELECT * FROM community.members` → `permission denied`) — bài kiểm tra trong P0. Flyway mỗi service: `spring.flyway.schemas=<schema>`, `default-schema=<schema>`.

## 4. Biến môi trường và secret

| Biến | Dùng bởi | Ghi chú |
|------|---------|---------|
| `POSTGRES_ADMIN_PASSWORD` | postgres | |
| `<SVC>_DB_PASSWORD` | postgres init, service tương ứng | 4 biến |
| `<SVC>_MQ_PASSWORD` | rabbitmq definitions, service | Mỗi service một user RabbitMQ |
| `JWT_PRIVATE_KEY_PATH`, `JWT_KEY_ID` | identity | File PEM mount vào container (`secrets:` của compose) |
| `CLOUDINARY_CLOUD_NAME` | identity, messaging | Tên cloud (công khai, cũng trả cho frontend trong response chữ ký) |
| `CLOUDINARY_API_KEY` | identity, messaging | API key (công khai, trả cho frontend) |
| `CLOUDINARY_API_SECRET` | identity, messaging | **Bí mật**, chỉ dùng để ký, không bao giờ trả ra ngoài |
| `CLOUDINARY_UPLOAD_PRESET_AVATAR` | identity (map vào `CLOUDINARY_UPLOAD_PRESET`) | Tên upload preset *signed* cho avatar (ảnh, ≤ 5 MB) |
| `CLOUDINARY_UPLOAD_PRESET_ATTACHMENT` | messaging (map vào `CLOUDINARY_UPLOAD_PRESET`) | Tên upload preset *signed* cho đính kèm (≤ 25 MB) |
| `CLOUDINARY_FOLDER_ROOT` | identity, messaging | Thư mục gốc, ví dụ `nexus-dev` (local) / `nexus` (demo) để tách dữ liệu khi dùng chung 1 account |
| `GRPC_TOKEN_REALTIME` | realtime-gateway (gửi), community + messaging (kiểm) | Token gRPC không có user context; allowlist: Community `CheckServerMembership`, Messaging `CheckChannelAccess` |
| `GRPC_TOKEN_PRESENCE` | presence-service (gửi), community (kiểm) | Allowlist: Community `ListServerIdsForUser` |
| `GRPC_TOKEN_MESSAGING` | messaging-service (gửi), community (kiểm) | Allowlist: Community `CheckChannelPermissions`; ba token phải khác nhau (community từ chối khi trùng) |
| `COMMUNITY_GRPC_TARGET`, `MESSAGING_GRPC_TARGET`, `IDENTITY_GRPC_TARGET` | service gọi gRPC | Dạng `dns:///community-service:9092` |
| `REDIS_HOST` | api-gateway (rate limit) | `redis` trong compose |
| `REDIS_PORT` | compose | Cổng publish Redis trên host (mặc định 6379), không phải cấu hình của gateway |
| `IDENTITY_URI`, `COMMUNITY_URI` | api-gateway | Địa chỉ nội bộ để định tuyến, ví dụ `http://identity-service:8081`, `http://community-service:8082` |
| `JWKS_URI` | api-gateway, community-service | `http://identity-service:8081/.well-known/jwks.json` |
| `CORS_ALLOWED_ORIGINS` | api-gateway, identity-service | identity-service dùng để kiểm Origin ở `/auth/refresh` (so khớp chuỗi chính xác) |
| `NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_WS_URL` | frontend (build arg) | |
| `TAG`, `FRONTEND_TAG`, `FRONTEND_DIR` | compose | |
| `OTEL_ENDPOINT` | identity, community, api-gateway (compose map vào `OTEL_EXPORTER_OTLP_ENDPOINT`) | Để trống thì exporter tắt; bật bằng `OTEL_ENDPOINT=http://otel-lgtm:4318` cùng `--profile observability` |

| Môi trường | Nơi giữ secret |
|-----------|---------------|
| Local | `.env` (git-ignore), khóa JWT sinh bởi `infra/scripts/gen-jwt-keys.sh` vào `infra/secrets/` (git-ignore) |
| CI | GitHub Actions Secrets (chỉ cần cho deploy demo; test dùng Testcontainers + giá trị giả) |
| VM demo | `.env` trên VM quyền `600`, compose `secrets:` cho khóa JWT |

`.env.example` commit với giá trị giả. Không bao giờ commit `.env`, khóa PEM, `CLOUDINARY_API_SECRET` thật.

**Cấu hình Cloudinary (người dùng đã có tài khoản — D-20):**
1. Cloudinary Console → *Settings → API Keys*: lấy cloud name, API key, API secret → điền vào `.env` (không commit).
2. *Settings → Upload → Upload presets*: tạo 2 preset chế độ **Signed** — `nexus_avatar` (allowed formats `jpg,png,webp,gif`, max file size 5 MB) và `nexus_attachment` (max 25 MB) → điền tên vào `CLOUDINARY_UPLOAD_PRESET_AVATAR`, `CLOUDINARY_UPLOAD_PRESET_ATTACHMENT`.
3. `.env.example` chỉ chứa tên biến với giá trị giả (`CLOUDINARY_API_SECRET=change-me`).
4. CI không cần Cloudinary thật (test dùng `UploadSigner` giả); chỉ VM demo cần secret thật.

Folder trong chữ ký có dạng `${CLOUDINARY_FOLDER_ROOT}/avatars/{userId}` và `${CLOUDINARY_FOLDER_ROOT}/attachments/{channelId}/{userId}`; tài liệu khác viết tắt là `nexus/...`.

## 5. RabbitMQ

- `definitions.json`: vhost `nexus`, user mỗi service (`identity_svc`, ...) với quyền `configure/write/read` dạng regex chỉ trên tên exchange/queue của chính nó; user `admin` cho UI.
- `enabled_plugins`: `rabbitmq_management`, `rabbitmq_prometheus`.
- Exchange/queue do ứng dụng khai báo khi khởi động (xem [event-catalog.md](../02-architecture/event-catalog.md#8-ma-trận-binding-queue--exchange-routing-key)).
