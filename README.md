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
| Chạy Postgres/Redis/RabbitMQ | `docker compose up -d --wait` |
| Kiểm cách ly schema | `./infra/scripts/verify-isolation.sh` |
| Xóa sạch kể cả dữ liệu | `docker compose down -v` |

Khóa JWT sinh bởi `gen-jwt-keys.sh` có quyền `644` để container non-root (uid 10001) đọc được bind mount; chỉ dùng cho local, không commit. Mọi cổng publish (Postgres, Redis, RabbitMQ) chỉ bind `127.0.0.1`. Cổng Postgres trên host mặc định `5432`, đổi bằng `POSTGRES_PORT` trong `.env` nếu bị chiếm.
