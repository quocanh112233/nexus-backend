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
| Chạy Postgres/Redis/RabbitMQ + identity-service | `docker compose up -d --build --wait` |
| Demo P1 (đăng ký, đăng nhập, refresh, reuse) | `./infra/scripts/demo-p1.sh` |
| Kiểm cách ly schema | `./infra/scripts/verify-isolation.sh` |
| Xóa sạch kể cả dữ liệu | `docker compose down -v` |

Khóa JWT sinh bởi `gen-jwt-keys.sh` có quyền `644` để container non-root (uid 10001) đọc được bind mount; chỉ dùng cho local, không commit. Mọi cổng publish (Postgres, Redis, RabbitMQ, identity :8081) chỉ bind `127.0.0.1`. Cổng Postgres trên host mặc định `5432`, đổi bằng `POSTGRES_PORT` trong `.env` nếu bị chiếm.

`COOKIE_SECURE=false` trong `.env` chỉ dùng cho local chạy HTTP (cookie Secure qua HTTP thì curl không gửi lại); đặt `true` khi chạy sau HTTPS. `CORS_ALLOWED_ORIGINS` phải khớp chính xác Origin của trình duyệt.

## identity-service (P1)

Chạy ở `http://127.0.0.1:8081`, Swagger UI tại [http://127.0.0.1:8081/swagger-ui.html](http://127.0.0.1:8081/swagger-ui.html). Cổng actuator `8181` không publish ra host. Biến `CLOUDINARY_*` để trống thì service vẫn chạy nhưng endpoint avatar chưa dùng được.

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
