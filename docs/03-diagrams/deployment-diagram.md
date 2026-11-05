# Deployment diagram

File này mô tả cách triển khai: Docker Compose ở local, pipeline CI/CD GitHub Actions → GHCR, và bản demo công khai trên 1 VM. Chi tiết: [infra-structure.md](../04-project-structure/infra-structure.md), [devops-plan.md](../05-plan/devops-plan.md).

## 1. Docker Compose local

```mermaid
flowchart TB
    Dev["Máy dev: docker compose up -d --build"]
    subgraph Host["Docker host"]
        subgraph Net["network nexus-net"]
            subgraph InfraC["compose.infra.yaml"]
                PG[("postgres:16 :5432, init script tạo schema + user")]
                RD[("redis:7 :6379")]
                MQ{{"rabbitmq:4-management :5672, :15672"}}
                OBS["otel-lgtm :3001, profile observability"]
            end
            subgraph AppC["compose.app.yaml"]
                GW["api-gateway :8080"]
                ID["identity-service"]
                CM["community-service"]
                MS["messaging-service"]
                PR["presence-service"]
                NT["notification-service"]
                RT["realtime-gateway"]
                FE["frontend :3000"]
            end
        end
    end
    Dev --> Host
    ID -- "depends_on healthy" --> PG
    CM -- "depends_on healthy" --> PG
    MS -- "depends_on healthy" --> PG
    NT -- "depends_on healthy" --> PG
    MS -- "depends_on healthy" --> MQ
    PR -- "depends_on healthy" --> RD
    GW -- "depends_on healthy" --> ID
    FE -- "depends_on healthy" --> GW
```

Chỉ `api-gateway :8080`, `frontend :3000`, UI RabbitMQ `:15672`, Grafana `:3001` (và Postgres/Redis cho debug) được publish ra host. Cổng gRPC không publish.

## 2. Pipeline CI/CD

```mermaid
flowchart LR
    PR["Push hoặc Pull Request"] --> PF{"Path filter: thư mục nào đổi?"}
    PF -- "services/messaging-service/**" --> J1["Job messaging-service"]
    PF -- "proto/** hoặc libs/** hoặc pom.xml gốc" --> JALL["Job cho mọi service phụ thuộc"]
    PF -- "proto/**" --> BUF["buf lint + buf breaking"]
    J1 --> S1["Checkout, setup JDK 21, cache .m2"]
    S1 --> S2["mvn -pl services/messaging-service -am verify: sinh code proto, compile, Spotless, unit, Testcontainers, ArchUnit, JaCoCo"]
    S2 --> S3["docker buildx build, cache gha"]
    S3 --> S4["Trivy scan image"]
    S4 --> S5{"Nhánh main hoặc tag v*?"}
    S5 -- "Không, chỉ PR" --> DONE["Báo kết quả lên PR"]
    S5 -- "Có" --> S6["Push GHCR: sha-xxxxxxx, latest, vX.Y.Z"]
    S6 --> DEMO["Tùy chọn: deploy demo VM qua SSH, docker compose pull và up"]
```

## 3. Demo công khai (P-28)

```mermaid
flowchart LR
    User["Nhà tuyển dụng"] --> V["Vercel: nexus-frontend"]
    User --> CADDY
    V -- "REST, WebSocket" --> CADDY
    subgraph VM["VM 4 GB RAM trở lên: Docker Compose"]
        CADDY["Caddy :443, TLS tự động"] --> GW["api-gateway"]
        GW --> SVC["6 container service và gateway"]
        SVC --> INF[("Postgres, Redis, RabbitMQ")]
    end
    GHCR[("GHCR images")] -. "docker compose pull" .-> VM
```
