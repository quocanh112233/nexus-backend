# ADR-007: Chiến lược đóng gói Docker

Ghi lại cách đóng gói mọi thành phần thành image Docker và chạy toàn hệ thống bằng một lệnh.

- **Trạng thái:** Accepted cho yêu cầu "bắt buộc Docker" (D-14); chi tiết kỹ thuật P-19, P-32 vẫn chờ duyệt

## Bối cảnh
- Bắt buộc đóng gói Docker. Nhà tuyển dụng cần chạy được bằng một lệnh.
- 7 thành phần backend + frontend, chung parent POM, proto và libs.

## Quyết định
- **Mỗi service/gateway backend có `Dockerfile` riêng** trong thư mục của nó (`services/<svc>/Dockerfile`, `gateways/<gw>/Dockerfile`); **build context = gốc repo `nexus-backend`** (cần parent POM, `proto/`, `libs/`).
- Multi-stage:
  1. `build`: `maven:3.9-eclipse-temurin-21`, copy POM trước → `mvn dependency:go-offline` (layer cache), dùng `RUN --mount=type=cache,target=/root/.m2`, rồi `mvn -pl <module> -am package -DskipTests`.
  2. `extract`: `java -Djarmode=tools -jar app.jar extract --layers --launcher` → tách layer `dependencies / spring-boot-loader / snapshot-dependencies / application`.
  3. `runtime`: `eclipse-temurin:21-jre-alpine`, copy từng layer (ít thay đổi trước), user non-root `uid 10001`, `HEALTHCHECK` gọi `/actuator/health/readiness`, `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"`.
- Frontend: `Dockerfile` ở gốc `nexus-frontend`, Next.js `output: "standalone"`, `node:22-alpine`, user non-root `nextjs`, `HEALTHCHECK` gọi `/api/health`.
- Compose: `compose.yaml` ở gốc `nexus-backend` dùng `include` gộp `infra/compose.infra.yaml` (Postgres, Redis, RabbitMQ, otel-lgtm theo profile) và `infra/compose.app.yaml` (service, gateway, frontend). Một lệnh: `docker compose up -d --build`.
- CI: build image theo path filter, push **GHCR** với tag `sha-<7 ký tự>`, `latest` (nhánh main), `vX.Y.Z` (git tag); quét **Trivy** (fail khi có lỗ hổng CRITICAL đã có bản vá).
Chi tiết: [infra-structure.md](../../04-project-structure/infra-structure.md), [devops-plan.md](../../05-plan/devops-plan.md).

## Hệ quả
- (+) Image nhỏ (~200 MB JRE alpine), đổi code chỉ rebuild layer `application`.
- (+) Chạy giống nhau ở local, CI và VM demo (P-28).
- (-) 7 Dockerfile gần giống nhau → chấp nhận (yêu cầu mỗi service có Dockerfile riêng); khi sửa một file, sửa cả 7 (checklist trong PR template).
- (-) Build context là cả repo → cần `.dockerignore` chặt (`**/target`, `docs/`, `.git`).
