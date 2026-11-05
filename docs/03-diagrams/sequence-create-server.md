# Sequence: tạo server

File này mô tả tạo server: một transaction trong Community (dữ liệu + outbox), sau đó event lan tới các service khác (WF-03, FR-020).

```mermaid
sequenceDiagram
    autonumber
    actor U as Người dùng
    participant FE as Frontend
    participant GW as api-gateway
    participant CM as community-service
    participant CDB as Postgres community
    participant MQ as RabbitMQ community.events
    participant MS as messaging-service
    participant NT as notification-service
    participant RT as realtime-gateway

    U->>FE: nhập tên server
    FE->>GW: POST /api/v1/servers
    GW->>CM: chuyển tiếp, Bearer JWT
    CM->>CM: Server.create tạo @everyone, category, kênh general, member owner
    CM->>CDB: BEGIN, INSERT servers, roles, categories, channels, members
    CM->>CDB: INSERT outbox_event x4, COMMIT
    CM-->>FE: 201 server, channels, roles
    FE->>FE: thêm vào serversStore, điều hướng tới kênh general

    Note over CM,MQ: Bất đồng bộ, vài trăm ms sau
    CM->>MQ: relay publish ServerCreated, CategoryCreated, ChannelCreated, MemberJoined
    MQ->>MS: ChannelCreated
    MS->>MS: INSERT channel_refs kind SERVER
    MQ->>NT: ChannelCreated, MemberJoined
    NT->>NT: INSERT channel_activity, server_members, read_states
    MQ->>RT: ServerCreated
    RT->>FE: /user/queue/events tới thiết bị khác của owner
```

Điểm học: nếu gửi tin vào kênh `general` **ngay** sau khi tạo mà `channel_refs` chưa kịp có → Messaging fallback gọi gRPC `CheckChannelPermissions` (trả `server_id`) rồi tự ghi `channel_refs`. Đây là cách xử lý eventual consistency "đọc-sau-ghi" giữa hai service.
