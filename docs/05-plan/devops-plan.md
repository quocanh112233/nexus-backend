# Kế hoạch DevOps (Docker, Compose, CI/CD)

File này mô tả việc đóng gói Docker, Docker Compose, pipeline GitHub Actions cho từng service và frontend (build, sinh code proto, test, lint, image, quét lỗ hổng, push GHCR, caching, path filter) và thứ tự làm theo phase. Quyết định: [ADR-007](../00-overview/adr/ADR-007-docker-packaging.md).

## 1. Nguyên tắc

- **Docker từ ngày đầu:** service nào ra đời (P1, P2, P4, P6, P8) đều có `Dockerfile`, mục trong `compose.app.yaml` và workflow CI **ngay trong phase đó** — không dồn về cuối.
- Mọi image: multi-stage, runtime nhỏ (`eclipse-temurin:21-jre-alpine` / `node:22-alpine`), non-root (uid 10001), `HEALTHCHECK`, layer cache (dependency trước, source sau).
- Một lệnh chạy toàn hệ thống: `docker compose up -d --build`.

## 2. Docker theo phase

| Phase | Việc | Tiêu chí xong |
|-------|------|---------------|
| P0 | `compose.yaml` (include), `compose.infra.yaml` (postgres + init, redis, rabbitmq, healthcheck), `.dockerignore`, `.env.example` | 3 container `healthy` |
| P0 | Frontend `Dockerfile` (standalone) | `docker run` frontend → `/api/health` 200 |
| P1 | `services/identity-service/Dockerfile` theo mẫu [backend-structure.md](../04-project-structure/backend-structure.md#6-dockerfile-mỗi-service-adr-007) | Image < 250 MB, `docker exec ... id` ≠ root, rebuild sau khi sửa 1 file Java chỉ thay layer `application` |
| P2 | Dockerfile community-service, api-gateway; profile `observability` với `otel-lgtm` | `docker compose --profile observability up` → Grafana có log |
| P4 | Dockerfile messaging-service; `depends_on` theo chuỗi gRPC | Compose healthy từ máy sạch |
| P6 | Dockerfile realtime-gateway, presence-service; thử `--scale realtime-gateway=2` | Hai instance hoạt động sau gateway |
| P8 | Dockerfile notification-service; frontend vào `compose.app.yaml` | **Toàn hệ thống + frontend bằng 1 lệnh** |
| P9 | Giới hạn `memory` từng container; `compose-smoke.yml`; Caddy + compose cho VM demo | NFR-005; smoke test xanh |

## 3. Pipeline GitHub Actions — `nexus-backend`

### 3.1 Workflow theo service + path filter

Mỗi service một file workflow mỏng gọi reusable `_backend-service.yml`. Path filter dùng `on.<event>.paths` có sẵn của GitHub (không cần action ngoài):

```yaml
# .github/workflows/messaging-service.yml
name: messaging-service
on:
  push:
    branches: [main]
    tags: ["v*"]
    paths: &paths
      - "services/messaging-service/**"
      - "proto/**"
      - "libs/**"
      - "pom.xml"
      - ".github/workflows/messaging-service.yml"
      - ".github/workflows/_backend-service.yml"
  pull_request:
    paths: *paths
jobs:
  build:
    uses: ./.github/workflows/_backend-service.yml
    with:
      module: services/messaging-service
      image: nexus-messaging-service
    permissions:
      contents: read
      packages: write
      security-events: write
```

Lưu ý: GitHub Actions hỗ trợ YAML anchor từ 2025; nếu gặp lỗi, lặp lại danh sách `paths` cho `pull_request`.

Đổi `proto/**`, `libs/**` hoặc `pom.xml` gốc → mọi service phụ thuộc đều build lại (đúng ý: hợp đồng đổi thì phải kiểm tra mọi bên). Đổi `docs/**` → không build gì.

### 3.2 Reusable workflow `_backend-service.yml`

| Bước | Action / lệnh | Ghi chú |
|------|---------------|---------|
| Checkout | `actions/checkout@v4` | `fetch-depth: 0` không cần |
| JDK + cache Maven | `actions/setup-java@v4` (`temurin`, `21`, `cache: maven`) | Cache `~/.m2` theo hash `**/pom.xml` |
| Sinh code proto + build + test | `./mvnw -B -pl ${{ inputs.module }} -am verify` | `-am` build `proto` (sinh stub qua `protobuf-maven-plugin`) và `libs`; chạy Spotless check, unit, Testcontainers IT (Docker có sẵn trên `ubuntu-latest`), ArchUnit, JaCoCo |
| Báo cáo test | `actions/upload-artifact@v4` (surefire/failsafe/jacoco) + tóm tắt coverage vào job summary | |
| Docker meta | `docker/metadata-action@v5` | Tag: `sha-<7>` (mọi lần), `latest` (main), `{{version}}` (tag `v*`) |
| Buildx | `docker/setup-buildx-action@v3` | |
| Login GHCR | `docker/login-action@v3` (`ghcr.io`, `GITHUB_TOKEN`) | Chỉ khi không phải PR từ fork |
| Build (+ push) | `docker/build-push-action@v6`: `context: .`, `file: <module>/Dockerfile`, `cache-from/to: type=gha,scope=<image>`, `push: ${{ github.event_name != 'pull_request' }}`, `load: true` cho PR | Image tên `ghcr.io/quocanh112233/<image>` |
| Quét lỗ hổng | `aquasecurity/trivy-action`: `severity: CRITICAL,HIGH`, `ignore-unfixed: true`, `exit-code: 1` chỉ với CRITICAL; xuất SARIF → `github/codeql-action/upload-sarif` | Kết quả hiện ở tab Security |

### 3.3 Workflow khác

| Workflow | Kích hoạt | Việc |
|----------|----------|------|
| `proto.yml` | `paths: proto/**` | `bufbuild/buf-action`: `buf lint proto`, `buf breaking proto --against '.git#branch=main,subdir=proto'` (PR) |
| `compose-smoke.yml` | push `main` + nightly (`schedule`) | `docker compose up -d --build --wait` → script smoke (đăng ký, tạo server, gửi tin qua gateway) → Playwright e2e (từ P9) → `docker compose down -v` |
| `dependabot.yml` | hằng tuần | Maven, GitHub Actions, Docker base image |

### 3.4 Caching tổng hợp

| Cache | Cơ chế | Hiệu quả |
|------|--------|---------|
| Maven dependency | `setup-java cache: maven` | Bỏ tải lại dependency |
| Docker layer | `type=gha` theo scope từng image | Chỉ rebuild layer thay đổi |
| Trong Dockerfile | `RUN --mount=type=cache,target=/root/.m2` + copy POM trước | Layer dependency giữ nguyên khi chỉ đổi code |
| Path filter | `paths:` | Không build service không đổi |

## 4. Pipeline — `nexus-frontend`

`frontend.yml` (push/PR):

| Bước | Lệnh |
|------|------|
| Setup | `actions/setup-node@v4` (`node 22`, `cache: pnpm`), `pnpm install --frozen-lockfile` |
| Lint + typecheck | `pnpm lint`, `pnpm tsc --noEmit` |
| Test | `pnpm vitest run` |
| Image | metadata → build-push (cache `gha`, build arg `NEXT_PUBLIC_*`) → Trivy → push GHCR `nexus-frontend` (main, tag) |

## 5. CD cho demo (tùy chọn, P9)

Workflow `deploy-demo.yml` chạy tay (`workflow_dispatch`) hoặc khi có tag `v*`: SSH vào VM (secret `DEMO_SSH_KEY`) → `docker compose pull && docker compose up -d`. Không có Kubernetes (D-10, P-28).

## 6. Tiêu chí xong của DevOps

- [ ] Mỗi service + frontend có Dockerfile riêng đạt chuẩn ở mục 1.
- [ ] `docker compose up -d --build` từ máy sạch → mọi container `healthy`.
- [ ] Mỗi workflow chỉ chạy khi thư mục liên quan đổi; đổi `proto/` kích hoạt mọi service phụ thuộc + `buf breaking`.
- [ ] Image trên GHCR có tag `sha-*`, `latest`, `vX.Y.Z`.
- [ ] Trivy chạy trong mọi pipeline image.
- [ ] Badge CI trong README.
