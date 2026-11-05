# Sequence: gửi tin nhắn realtime qua nhiều service

File này mô tả luồng quan trọng nhất của Nexus: REST vào Messaging, gRPC kiểm tra quyền ở Community, outbox → RabbitMQ → realtime-gateway (nhiều instance) và Notification (WF-05, FR-050, FR-056, FR-059).

## 1. Luồng thường

```mermaid
sequenceDiagram
    autonumber
    actor A as User A
    participant FEA as Frontend A
    participant GW as api-gateway
    participant MS as messaging-service
    participant R as Redis
    participant CM as community-service
    participant MDB as Postgres messaging
    participant MQ as RabbitMQ messaging.events
    participant RT1 as realtime-gateway 1
    participant RT2 as realtime-gateway 2
    participant NT as notification-service
    participant FEB as Frontend B

    A->>FEA: gõ tin có mention B
    FEA->>FEA: hiện tin tạm trạng thái đang gửi, sinh nonce
    FEA->>GW: POST /api/v1/channels/C/messages content, nonce
    GW->>MS: chuyển tiếp, Bearer JWT
    MS->>MDB: SELECT channel_refs C, kind SERVER, server S
    MS->>R: GET perm:ver:S rồi GET perm:S:ver:C:A
    alt cache miss
        MS->>CM: gRPC CheckChannelPermissions user A, channel C, deadline 300ms
        CM->>CM: PermissionCalculator
        CM-->>MS: server S, granted VIEW, SEND, ...
        MS->>R: SET perm:S:ver:C:A EX 60
    end
    MS->>MS: kiểm tra SEND_MESSAGES, parse mention, validate
    MS->>MDB: BEGIN, INSERT messages, message_mentions, outbox MessageCreated, COMMIT
    MS-->>FEA: 201 message
    FEA->>FEA: thay tin tạm bằng tin thật theo nonce

    MS->>MQ: relay publish message.created
    par fan-out tới mọi instance realtime
        MQ->>RT1: queue realtime-gateway.rt1
        RT1->>FEA: /topic/channels/C MessageCreated, bỏ qua vì trùng nonce
    and
        MQ->>RT2: queue realtime-gateway.rt2
        RT2->>FEB: /topic/channels/C MessageCreated
    and
        MQ->>NT: queue notification.messaging.events
        NT->>NT: inbox check, cập nhật channel_activity, mention_count của B
        NT->>MQ: relay publish MentionCreated lên notification.events
    end
    MQ->>RT2: MentionCreated
    RT2->>FEB: /user/queue/events badge mention
```

## 2. Luồng lỗi: Community không trả lời

```mermaid
sequenceDiagram
    autonumber
    participant FEA as Frontend A
    participant MS as messaging-service
    participant CB as Circuit breaker
    participant CM as community-service

    FEA->>MS: POST /api/v1/channels/C/messages
    MS->>CB: CheckChannelPermissions
    alt circuit đóng
        CB->>CM: gRPC, deadline 300ms
        CM--xCB: không phản hồi
        CB->>CB: DEADLINE_EXCEEDED, ghi nhận lỗi
        CB-->>MS: DependencyUnavailableException
    else circuit mở sau nhiều lỗi
        CB-->>MS: từ chối ngay, không gọi mạng
    end
    MS-->>FEA: 503 DEPENDENCY_UNAVAILABLE
    FEA->>FEA: đánh dấu tin gửi lỗi, nút Thử lại dùng lại nonce
```

Nếu quyền đã có trong cache Redis, tin vẫn gửi được dù Community đang chết — đây là lợi ích của cache trong việc giảm phụ thuộc runtime.
