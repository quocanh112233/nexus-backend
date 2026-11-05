# Tech stack

File này liệt kê công nghệ dùng cho Nexus và lý do chọn. Cột "Nguồn" cho biết đã chốt (D-xx) hay đề xuất (P-xx; trạng thái duyệt xem decisions.md) — xem [decisions.md](decisions.md).

## Backend (trọng tâm)

| Hạng mục | Công nghệ | Nguồn |
|---------|-----------|-------|
| Ngôn ngữ / framework | Java 21, Spring Boot 4.1.x, Spring Cloud (bản tương thích Boot 4.1) | D-02, P-07 |
| Build | Maven multi-module | P-03 |
| Web (biên) | Spring Web MVC (REST), springdoc-openapi | D-08, P-27 |
| Đồng bộ nội bộ | gRPC + Protocol Buffers, Spring gRPC, `protobuf-maven-plugin` (ascopes), buf | D-08, P-10 |
| Bất đồng bộ | RabbitMQ 4.x (quorum queue), Spring AMQP | D-08, P-01 |
| Persistence | PostgreSQL 16, Spring Data JPA, Flyway | D-04, D-05 |
| Cache / state tạm | Redis 7 (Spring Data Redis) | P-29 |
| API Gateway | Spring Cloud Gateway (+ RequestRateLimiter dùng Redis) | P-17 |
| Realtime | Spring WebSocket + STOMP (`realtime-gateway`) | P-13 |
| Bảo mật | Spring Security, OAuth2 Resource Server (JWT RS256, JWKS), BCrypt | P-09 |
| Resilience | Resilience4j (circuit breaker, retry), gRPC deadline | P-24 |
| Upload | Cloudinary Java SDK (chỉ để ký) | D-06 |
| Observability | Micrometer, OpenTelemetry (OTLP), `grafana/otel-lgtm` | P-20 |
| Test | JUnit 5, AssertJ, Mockito, Testcontainers, ArchUnit, grpc-testing, k6 | P-21, P-31 |
| Format | Spotless (google-java-format) | |

## Frontend (tối giản, có chủ đích — P-30)

| Hạng mục | Công nghệ | Nguồn |
|---------|-----------|-------|
| Framework | Next.js 15 (App Router), TypeScript | D-03 |
| State | Zustand 5 | D-03 |
| UI | Tailwind CSS + shadcn/ui (dùng component có sẵn, không tự thiết kế) | P-18 |
| Realtime | `@stomp/stompjs` | P-18 |
| Validation | zod | P-18 |
| Test | Vitest (store), Playwright (1-2 e2e) | P-18 |

## Hạ tầng & vận hành

| Hạng mục | Công nghệ | Nguồn |
|---------|-----------|-------|
| Chạy local | Docker Compose | D-10 |
| CI/CD | GitHub Actions, image lên GHCR | D-09, P-19 |
| File | Cloudinary | D-06 |
| Demo công khai | 1 VM + Docker Compose + Caddy; frontend Vercel | P-28 |

## Cổng mặc định (local)

| Thành phần | HTTP | gRPC |
|-----------|------|------|
| api-gateway | 8080 | - |
| identity-service | 8081 | 9091 |
| community-service | 8082 | 9092 |
| messaging-service | 8083 | 9093 |
| presence-service | 8084 | - |
| notification-service | 8085 | - |
| realtime-gateway | 8086 (WebSocket `/ws`) | - |
| frontend | 3000 | - |
| PostgreSQL | 5432 | |
| Redis | 6379 | |
| RabbitMQ | 5672 (AMQP), 15672 (UI) | |
| Grafana (otel-lgtm) | 3001 (UI), 4317/4318 (OTLP) | |
