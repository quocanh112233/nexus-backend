# Cấu trúc repository

File này chốt cách tổ chức repo (đề xuất P-02, [ADR-005](../00-overview/adr/ADR-005-repo-structure.md)) và cây thư mục đầy đủ. Đây là **mô tả**, chưa tạo thư mục thật.

## 1. Lựa chọn

| Phương án | Ưu | Nhược | Kết luận |
|----------|----|-------|----------|
| Monorepo toàn bộ (backend + frontend) | 1 nơi, 1 PR cho thay đổi xuyên tầng | Không khớp 2 repo đã có; CI frontend/backend lẫn nhau | Không chọn |
| Multi-repo mỗi service 1 repo | Độc lập tuyệt đối | Đổi proto/event phải sửa nhiều repo, quản lý version lib chung rất nặng cho 1 người | Không chọn |
| **2 repo: `nexus-backend` (monorepo backend) + `nexus-frontend`** | Khớp thực tế (D-13); đổi proto + server + client trong 1 PR; frontend độc lập | Cần path filter; phải giữ kỷ luật không coupling qua `libs/` | **Đề xuất (P-02)** |

Thư mục làm việc local hiện tại: `nexus/backend` (clone `nexus-backend`, **chứa `docs/` này** — P-26 đã chốt), `nexus/frontend` (clone `nexus-frontend`; tài liệu thiết kế frontend cũng nằm ở `nexus-backend/docs/04-project-structure/frontend-structure.md` và `05-plan/frontend-plan.md`).

## 2. Cây thư mục `nexus-backend`

```text
nexus-backend/
├── README.md                         # Kiến trúc tổng quan, sơ đồ, chạy bằng 1 lệnh, badge CI
├── compose.yaml                      # include infra + app → "docker compose up -d --build"
├── .dockerignore                     # **/target, .git, docs/, *.md
├── .env.example                      # biến môi trường mẫu (không chứa secret thật)
├── pom.xml                           # parent POM: modules, dependencyManagement, plugin chung
├── mvnw, .mvn/                       # Maven wrapper
├── proto/                            # module nexus-proto (hợp đồng gRPC)
│   ├── pom.xml
│   ├── buf.yaml
│   └── src/main/proto/nexus/{common,identity,community,messaging}/v1/*.proto
├── libs/
│   ├── nexus-common-web/             # Problem Details, exception handler, security resource-server config
│   ├── nexus-common-messaging/       # envelope, outbox writer/relay, inbox (OutboxEventDao, ProcessedEventDao), cấu hình RabbitMQ chung
│   ├── nexus-common-grpc/            # interceptor JWT/request-id/MDC, map exception ↔ status
│   └── nexus-test-support/           # base class Testcontainers, fixture chung (scope test)
├── services/
│   ├── identity-service/
│   │   ├── Dockerfile
│   │   ├── pom.xml
│   │   └── src/{main,test}/...       # xem backend-structure.md
│   ├── community-service/            # Dockerfile, pom.xml, src/
│   ├── messaging-service/            # Dockerfile, pom.xml, src/
│   ├── presence-service/             # Dockerfile, pom.xml, src/
│   └── notification-service/         # Dockerfile, pom.xml, src/
├── gateways/
│   ├── api-gateway/                  # Dockerfile, pom.xml, src/
│   └── realtime-gateway/             # Dockerfile, pom.xml, src/
├── contracts/
│   └── events/<producer>/<EventType>.v1.json   # payload mẫu cho contract test
├── infra/
│   ├── compose.infra.yaml            # postgres, redis, rabbitmq, otel-lgtm (profile observability)
│   ├── compose.app.yaml              # 7 thành phần backend + frontend
│   ├── compose.override.example.yaml # mở cổng debug, scale, v.v.
│   ├── postgres/init/01-create-schemas-and-users.sh
│   ├── rabbitmq/{rabbitmq.conf,definitions.json,enabled_plugins}
│   ├── observability/dashboards/nexus-overview.json
│   ├── caddy/Caddyfile               # cho VM demo
│   └── scripts/{gen-jwt-keys.sh,wait-for-healthy.sh,seed-demo-data.sh}
├── perf/
│   └── k6/{send-message.js,history.js,ws-fanout.js}
├── docs/                             # toàn bộ tài liệu thiết kế (P-26), docs/perf/ kết quả đo, docs/journal.md
└── .github/
    ├── workflows/
    │   ├── _backend-service.yml      # reusable workflow: build, test, image, scan, push
    │   ├── identity-service.yml      # gọi reusable, path filter riêng
    │   ├── community-service.yml
    │   ├── messaging-service.yml
    │   ├── presence-service.yml
    │   ├── notification-service.yml
    │   ├── api-gateway.yml
    │   ├── realtime-gateway.yml
    │   ├── proto.yml                 # buf lint + breaking
    │   └── compose-smoke.yml         # dựng cả hệ thống, chạy smoke test (nightly + main)
    ├── dependabot.yml
    └── pull_request_template.md
```

## 3. Cây thư mục `nexus-frontend`

```text
nexus-frontend/
├── README.md
├── Dockerfile                        # multi-stage, Next.js standalone, non-root
├── .dockerignore
├── .env.example                      # NEXT_PUBLIC_API_URL, NEXT_PUBLIC_WS_URL
├── package.json, pnpm-lock.yaml
├── next.config.ts                    # output: "standalone"
├── src/                              # xem frontend-structure.md
├── e2e/                              # Playwright
└── .github/workflows/frontend.yml    # lint, typecheck, test, build image, Trivy, push GHCR
```

## 4. Vị trí Dockerfile (tóm tắt)

| Thành phần | Dockerfile | Build context |
|-----------|-----------|---------------|
| 5 service | `nexus-backend/services/<svc>/Dockerfile` | gốc `nexus-backend` |
| 2 gateway | `nexus-backend/gateways/<gw>/Dockerfile` | gốc `nexus-backend` |
| Frontend | `nexus-frontend/Dockerfile` | gốc `nexus-frontend` |

## 5. Luật repo

- `libs/` **không chứa domain** (không entity, không event class nghiệp vụ). Chỉ hạ tầng kỹ thuật.
- Service không phụ thuộc Maven vào service khác; chỉ vào `proto`, `libs/*`.
- Code sinh từ proto không commit.
- Mỗi PR đổi `proto/` hoặc `contracts/` phải cập nhật [event-catalog.md](../02-architecture/event-catalog.md) / [grpc-design.md](../02-architecture/grpc-design.md).
