# Sequence: tham gia server qua invite

File này mô tả tạo invite, xem trước và tham gia server, gồm xử lý đồng thời khi nhiều người dùng cùng một invite có giới hạn lượt (WF-04, FR-026..FR-028).

```mermaid
sequenceDiagram
    autonumber
    actor A as Thành viên A
    actor B as Người dùng B
    participant FE as Frontend
    participant GW as api-gateway
    participant CM as community-service
    participant CDB as Postgres community
    participant MQ as RabbitMQ
    participant NT as notification-service
    participant RT as realtime-gateway

    A->>GW: POST /api/v1/servers/S/invites maxUses 10
    GW->>CM: chuyển tiếp
    CM->>CM: kiểm tra CREATE_INVITE
    CM->>CDB: INSERT invites code
    CM-->>A: 201 code, link /invite/code

    B->>FE: mở link /invite/code
    FE->>GW: GET /api/v1/invites/code
    GW->>CM: chuyển tiếp, không cần JWT
    CM-->>FE: 200 tên server, số thành viên
    B->>FE: bấm Tham gia
    FE->>GW: POST /api/v1/invites/code/accept
    GW->>CM: chuyển tiếp, Bearer JWT của B
    CM->>CDB: SELECT members S, B
    alt đã là member
        CM-->>FE: 200 server, không tăng uses
    else chưa là member
        CM->>CDB: BEGIN, UPDATE invites SET uses = uses + 1 WHERE còn hạn và còn lượt
        alt 0 dòng bị cập nhật
            CM->>CDB: ROLLBACK
            CM-->>FE: 410 INVITE_INVALID
        else 1 dòng
            CM->>CDB: INSERT members, INSERT outbox MemberJoined, COMMIT
            CM-->>FE: 200 server
        end
    end

    CM->>MQ: relay publish MemberJoined
    MQ->>NT: MemberJoined
    NT->>NT: INSERT server_members, tạo read_states mốc cho mọi kênh của S
    MQ->>RT: MemberJoined
    RT->>FE: /topic/servers/S cho member đang online
```

Điểm học về concurrency: `UPDATE ... WHERE uses < max_uses` là thao tác nguyên tử ở mức dòng → không cần lock ứng dụng. PK `(server_id, user_id)` chặn insert trùng nếu B bấm hai lần đồng thời (bắt `DuplicateKeyException` → coi như đã là member).
