# Component diagram

File này thể hiện các thành phần runtime và ba kiểu kết nối: REST/WebSocket ở biên (nét liền), gRPC đồng bộ nội bộ (nét đậm), event qua RabbitMQ (nét đứt). Mô tả: [architecture-overview.md](../02-architecture/architecture-overview.md).

## 1. Toàn cảnh

```mermaid
flowchart LR
    Browser["Trình duyệt - Next.js"]
    CLD[("Cloudinary")]

    subgraph Edge["Biên"]
        GW["api-gateway :8080"]
        RT["realtime-gateway :8086"]
    end

    subgraph Services["Domain services"]
        ID["identity-service :8081 / gRPC 9091"]
        CM["community-service :8082 / gRPC 9092"]
        MS["messaging-service :8083 / gRPC 9093"]
        PR["presence-service :8084"]
        NT["notification-service :8085"]
    end

    subgraph Infra["Hạ tầng"]
        PG[("PostgreSQL: schema identity, community, messaging, notification")]
        RD[("Redis")]
        MQ{{"RabbitMQ"}}
        OBS["otel-lgtm: Grafana, Loki, Tempo, Prometheus"]
    end

    Browser -- "REST /api/v1" --> GW
    Browser -- "WebSocket /ws" --> GW
    Browser -- "signed upload" --> CLD
    GW -- "REST" --> ID
    GW -- "REST" --> CM
    GW -- "REST" --> MS
    GW -- "REST" --> PR
    GW -- "REST" --> NT
    GW -- "WS proxy" --> RT
    GW --- RD

    MS == "gRPC PermissionService" ==> CM
    MS == "gRPC UserService" ==> ID
    RT == "gRPC ChannelAccessService" ==> MS
    RT == "gRPC PermissionService" ==> CM
    PR == "gRPC ListServerIdsForUser" ==> CM
    PR == "gRPC SocialService" ==> MS

    ID -.-> MQ
    CM -.-> MQ
    MS -.-> MQ
    PR -.-> MQ
    NT -.-> MQ
    RT -.-> MQ
    MQ -.-> MS
    MQ -.-> PR
    MQ -.-> NT
    MQ -.-> RT

    ID --- PG
    CM --- PG
    MS --- PG
    NT --- PG
    PR --- RD
    MS --- RD
    ID -- "ký, Admin API" --> CLD
    MS -- "ký, Admin API" --> CLD
    Services -.- OBS
```

## 2. Luồng event (exchange → consumer)

```mermaid
flowchart LR
    subgraph Producers["Exchange của producer"]
        E1{{"identity.events"}}
        E2{{"community.events"}}
        E3{{"messaging.events"}}
        E4{{"presence.events"}}
        E5{{"realtime.events"}}
        E6{{"notification.events"}}
    end
    subgraph Queues["Queue của consumer"]
        Q1[["messaging.community.events"]]
        Q2[["notification.community.events"]]
        Q3[["notification.messaging.events"]]
        Q4[["presence.realtime.events"]]
        Q6[["presence.community.events"]]
        Q7[["presence.messaging.events"]]
        Q5[["realtime-gateway.instanceId"]]
    end
    E2 --> Q1
    E2 --> Q2
    E3 --> Q3
    E5 --> Q4
    E2 --> Q6
    E3 --> Q7
    E2 --> Q5
    E3 --> Q5
    E4 --> Q5
    E5 --> Q5
    E6 --> Q5
    Q1 --> MSc["messaging-service"]
    Q2 --> NTc["notification-service"]
    Q3 --> NTc
    Q4 --> PRc["presence-service"]
    Q6 --> PRc
    Q7 --> PRc
    Q5 --> RTc["realtime-gateway"]
```

## 3. Cấu trúc bên trong một service (hexagonal + DDD)

```mermaid
flowchart TB
    subgraph IF["interfaces"]
        REST["REST controller"]
        GRPCS["gRPC server adapter"]
        LIS["RabbitMQ listener"]
    end
    subgraph APP["application"]
        UC["Use case / command handler"]
        PORT["Port: interface ra ngoài"]
    end
    subgraph DOM["domain"]
        AGG["Aggregate, entity, value object"]
        DS["Domain service"]
        EV["Domain event"]
        REPO["Repository interface"]
    end
    subgraph INF["infrastructure"]
        JPA["XxxRepositoryImpl + XxxMapper"]
        DAO["XxxDao - Spring Data, XxxJpaEntity"]
        GRPCC["gRPC client adapter + Resilience4j"]
        OUT["Outbox writer + relay"]
        CLDA["Cloudinary signer"]
    end
    REST --> UC
    GRPCS --> UC
    LIS --> UC
    UC --> AGG
    UC --> DS
    UC --> REPO
    UC --> PORT
    AGG --> EV
    JPA -. "implements" .-> REPO
    JPA --> DAO
    OUT --> DAO
    GRPCC -. "implements" .-> PORT
    CLDA -. "implements" .-> PORT
    OUT -. "implements" .-> PORT
```

Mũi tên phụ thuộc luôn hướng vào `domain`; DAO và JPA entity chỉ nằm trong `infrastructure` ([ADR-009](../00-overview/adr/ADR-009-dao-layer.md)); `domain` không phụ thuộc Spring, JPA hay class sinh từ proto (ArchUnit cưỡng chế).
