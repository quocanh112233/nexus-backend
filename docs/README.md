# Nexus — Tài liệu thiết kế

Đây là điểm vào của bộ tài liệu thiết kế Nexus: tóm tắt dự án, trạng thái và mục lục toàn bộ tài liệu.

## Tóm tắt

**Nexus** là web chat kiểu Discord (server, kênh text, role/permission, kick/ban, chat realtime, bạn bè/DM, presence riêng tư, unread/mention) dùng để **học kiến trúc microservice + DDD** và làm **dự án portfolio Backend Java**.

| Hạng mục | Nội dung |
|---------|---------|
| Backend | Java 21, Spring Boot 4.1, Maven multi-module |
| Service | identity, community, messaging, presence, notification + api-gateway, realtime-gateway |
| Giao tiếp | REST ở biên · **gRPC** đồng bộ nội bộ · **RabbitMQ** event bất đồng bộ (outbox/inbox) · WebSocket/STOMP realtime |
| Dữ liệu | PostgreSQL 1 instance, schema + user riêng mỗi service; Redis; Cloudinary signed upload |
| Frontend | Next.js + Zustand (tối giản, đủ demo) |
| Vận hành | Docker (mỗi service một Dockerfile), Docker Compose 1 lệnh, GitHub Actions → GHCR, Grafana otel-lgtm |
| Trọng tâm học | (1) kiến trúc microservice, (2) service giao tiếp ra sao, (3) message queue hoạt động thế nào |

**Trạng thái:** thiết kế xong; các quyết định chính đã được người dùng duyệt ([decisions.md](00-overview/decisions.md)). **Chỉ còn câu hỏi mở Q11** (nơi deploy demo, hoãn tới P9); một số đề xuất kỹ thuật nhỏ vẫn chờ duyệt nhưng không chặn việc bắt đầu ([risks-and-open-questions.md](05-plan/risks-and-open-questions.md#3-câu-hỏi-mở-cần-người-dùng-quyết-định)). Tài liệu nằm ở `nexus-backend/docs/`. Chưa có code. Phase tiếp theo: **P0 Nền móng** ([roadmap.md](05-plan/roadmap.md)).

Quy ước: `P-xx` = quyết định đề xuất (trạng thái duyệt ghi trong decisions.md); `D-xx` = đã chốt; `FR-xxx`/`NFR-xxx` = yêu cầu; `WF-xx` = workflow.

## Mục lục

### 00 — Tổng quan
| File | Nội dung |
|------|---------|
| [vision-and-goals.md](00-overview/vision-and-goals.md) | Tầm nhìn, mục tiêu học tập, goals / non-goals, backlog |
| [tech-stack.md](00-overview/tech-stack.md) | Công nghệ, cổng mặc định |
| [decisions.md](00-overview/decisions.md) | Quyết định đã chốt (D-xx) và đề xuất (P-xx, phần lớn đã duyệt) |
| [glossary.md](00-overview/glossary.md) | Ubiquitous language |
| [adr/](00-overview/adr/README.md) | ADR-001 broker · ADR-002 gRPC · ADR-003 schema-per-service · ADR-004 Cloudinary · ADR-005 repo · ADR-006 build tool · ADR-007 Docker · ADR-008 không Lombok · ADR-009 lớp DAO |

### 01 — Yêu cầu
| File | Nội dung |
|------|---------|
| [functional-requirements.md](01-requirements/functional-requirements.md) | FR-001..FR-103 theo module, MVP/Phase 2 |
| [non-functional-requirements.md](01-requirements/non-functional-requirements.md) | NFR hiệu năng, bảo mật, mở rộng, quan sát |
| [workflows.md](01-requirements/workflows.md) | WF-01..WF-12 luồng nghiệp vụ |
| [permission-model.md](01-requirements/permission-model.md) | Permission, role, overwrite, thuật toán |

### 02 — Kiến trúc
| File | Nội dung |
|------|---------|
| [architecture-overview.md](02-architecture/architecture-overview.md) | Thành phần, vì sao chia vậy, **khi nào gRPC / khi nào event** |
| [bounded-contexts-and-context-map.md](02-architecture/bounded-contexts-and-context-map.md) | DDD chiến lược |
| [services.md](02-architecture/services.md) | Aggregate, VO, event, REST, gRPC từng service |
| [grpc-design.md](02-architecture/grpc-design.md) | Proto, đặt tên, versioning, deadline, retry, lỗi, metadata, test |
| [messaging-deep-dive.md](02-architecture/messaging-deep-dive.md) | Message queue từ gốc, outbox/inbox, lỗi, Kafka vs RabbitMQ |
| [event-catalog.md](02-architecture/event-catalog.md) | Mọi event, exchange, queue, binding |
| [data-model.md](02-architecture/data-model.md) | Schema, bảng, index, Redis key |
| [api-conventions.md](02-architecture/api-conventions.md) | REST + STOMP |
| [realtime-design.md](02-architecture/realtime-design.md) | WebSocket gateway nhiều instance, typing, presence |
| [security.md](02-architecture/security.md) | JWT, phân quyền, trust boundary, rate limit, Cloudinary |
| [observability.md](02-architecture/observability.md) | Log, metric, trace |

### 03 — Sơ đồ (Mermaid)
| File | Nội dung |
|------|---------|
| [usecase.md](03-diagrams/usecase.md) | Use case theo module |
| [component-diagram.md](03-diagrams/component-diagram.md) | Thành phần, gRPC, event, cấu trúc trong service |
| [deployment-diagram.md](03-diagrams/deployment-diagram.md) | Compose local, CI/CD, demo VM |
| [context-map.md](03-diagrams/context-map.md) | Context map DDD |
| [erd.md](03-diagrams/erd.md) | ERD từng schema |
| [sequence-login-refresh.md](03-diagrams/sequence-login-refresh.md) | Đăng nhập + refresh token |
| [sequence-create-server.md](03-diagrams/sequence-create-server.md) | Tạo server |
| [sequence-join-server-invite.md](03-diagrams/sequence-join-server-invite.md) | Tham gia qua invite |
| [sequence-send-message.md](03-diagrams/sequence-send-message.md) | Gửi tin realtime qua nhiều service + luồng lỗi |
| [sequence-friend-request.md](03-diagrams/sequence-friend-request.md) | Kết bạn + DM |
| [sequence-upload-cloudinary.md](03-diagrams/sequence-upload-cloudinary.md) | Signed upload |
| [sequence-presence.md](03-diagrams/sequence-presence.md) | Presence có quy tắc riêng tư |
| [sequence-kick-ban.md](03-diagrams/sequence-kick-ban.md) | Kick, ban, join lại bị từ chối |
| [activity-registration.md](03-diagrams/activity-registration.md) | Luồng đăng ký |
| [activity-permission-check.md](03-diagrams/activity-permission-check.md) | Kiểm tra quyền khi gửi tin |
| [activity-friend-request-lifecycle.md](03-diagrams/activity-friend-request-lifecycle.md) | Vòng đời lời mời kết bạn |
| [activity-kick-ban-check.md](03-diagrams/activity-kick-ban-check.md) | Kiểm tra trước khi kick/ban |

### 04 — Cấu trúc dự án
| File | Nội dung |
|------|---------|
| [repo-structure.md](04-project-structure/repo-structure.md) | 2 repo, cây thư mục, vị trí Dockerfile |
| [backend-structure.md](04-project-structure/backend-structure.md) | Maven, proto, package DDD, Dockerfile |
| [frontend-structure.md](04-project-structure/frontend-structure.md) | App Router, Zustand, API/WS client, Dockerfile |
| [infra-structure.md](04-project-structure/infra-structure.md) | Compose, init Postgres, env, secret |
| [coding-conventions.md](04-project-structure/coding-conventions.md) | Đặt tên, **quy ước comment**, test, commit |

### 05 — Kế hoạch
| File | Nội dung |
|------|---------|
| [roadmap.md](05-plan/roadmap.md) | P0..P9, mốc M1..M4, tiêu chí sẵn sàng CV |
| [learning-path.md](05-plan/learning-path.md) | Khái niệm → phase, tài liệu đọc, thí nghiệm, DDD bằng ví dụ |
| [backend-plan.md](05-plan/backend-plan.md) | Checklist backend từng phase (phase gRPC, phase message queue) |
| [frontend-plan.md](05-plan/frontend-plan.md) | Checklist frontend tối giản |
| [devops-plan.md](05-plan/devops-plan.md) | Docker, Compose, GitHub Actions, GHCR, Trivy |
| [testing-strategy.md](05-plan/testing-strategy.md) | Unit, Testcontainers, contract, e2e |
| [portfolio-readiness.md](05-plan/portfolio-readiness.md) | Checklist portfolio, deploy demo, mẫu CV |
| [risks-and-open-questions.md](05-plan/risks-and-open-questions.md) | Rủi ro, cắt phạm vi, câu hỏi mở |

## Đọc theo mục đích

| Muốn | Đọc |
|-----|-----|
| Hiểu nhanh hệ thống | architecture-overview → component-diagram → sequence-send-message |
| Bắt đầu code | roadmap → backend-plan (P0) → repo-structure → infra-structure |
| Học gRPC | grpc-design → backend-plan P4 |
| Học message queue | messaging-deep-dive → event-catalog → backend-plan P5 |
