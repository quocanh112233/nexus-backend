# Sequence: cập nhật presence (có quy tắc riêng tư)

File này mô tả cách kết nối WebSocket dẫn tới trạng thái online/offline, chỉ giao cho **bạn bè và người chung server** (D-18), gồm nạp bù projection quan hệ và trường hợp gateway chết đột ngột (WF-10, FR-080, FR-081, FR-083). Chi tiết: [realtime-design.md](../02-architecture/realtime-design.md#6-presence-và-quyền-riêng-tư).

## 1. Online, giao đúng người

```mermaid
sequenceDiagram
    autonumber
    participant FE as Frontend user A
    participant RT as realtime-gateway rt1
    participant MQ as RabbitMQ
    participant PR as presence-service
    participant R as Redis
    participant CM as community-service
    participant MS as messaging-service
    participant FB as Frontend B chung server S
    participant FF as Frontend F là bạn của A
    participant FX as Frontend X người lạ

    FE->>RT: WebSocket /ws, STOMP CONNECT Bearer JWT
    RT->>RT: verify JWT, tạo sessionId
    RT-->>FE: CONNECTED
    RT->>MQ: SessionConnected lên realtime.events
    MQ->>PR: queue presence.realtime.events
    PR->>R: GET presence:rel:A:loaded
    alt projection chưa nạp hoặc hết hạn
        PR->>CM: gRPC ListServerIdsForUser A, x-service-token
        CM-->>PR: server_ids S
        PR->>MS: gRPC ListFriendIds A, x-service-token
        MS-->>PR: friend_ids F
        PR->>R: ghi rel servers, rel friends, loaded EX 24h
    end
    PR->>R: Lua script SADD session, đếm session của A
    alt A từ 0 lên 1 session
        PR->>MQ: PresenceChanged A ONLINE, serverIds S, friendIds F
        MQ->>RT: PresenceChanged
        RT->>FB: /topic/servers/S, B đã được kiểm membership khi subscribe
        RT->>FF: /user/queue/events của F
        Note over RT,FX: X không subscribe được /topic/servers/S và không nằm trong friendIds nên không nhận gì
    else A đã online ở thiết bị khác
        PR->>PR: không phát event
    end

    FX->>PR: GET /api/v1/presence?userIds=A qua api-gateway
    PR->>R: canSee X, A bằng SISMEMBER và SINTERCARD
    PR-->>FX: 200, danh sách rỗng, A bị lọc bỏ
```

## 2. Offline, heartbeat và quan hệ đổi

```mermaid
sequenceDiagram
    autonumber
    participant RT as realtime-gateway rt1
    participant MQ as RabbitMQ
    participant PR as presence-service
    participant R as Redis

    loop mỗi 30 giây
        RT->>MQ: GatewayHeartbeat rt1, danh sách sessionId
        MQ->>PR: GatewayHeartbeat
        PR->>R: SET presence:instance:rt1:alive EX 90, đồng bộ tập session
    end

    RT->>MQ: SessionDisconnected khi đóng tab
    MQ->>PR: SessionDisconnected
    PR->>R: Lua script SREM session
    PR->>MQ: PresenceChanged OFFLINE nếu còn 0 session, kèm serverIds và friendIds

    Note over RT,PR: rt1 chết đột ngột, không gửi được SessionDisconnected
    PR->>R: job mỗi 30 giây tìm instance mất khóa alive
    PR->>R: xóa mọi session của rt1
    PR->>MQ: PresenceChanged OFFLINE cho user không còn session

    Note over MQ,PR: Quan hệ đổi, ví dụ B bị ban khỏi server chung duy nhất S
    MQ->>PR: MemberBanned S, B qua presence.community.events
    PR->>R: SREM presence:rel:B:servers S nếu B đã nạp
    Note over RT: realtime-gateway cũng nhận MemberBanned và hủy subscription /topic/servers/S của B
```
