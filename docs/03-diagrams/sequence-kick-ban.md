# Sequence: kick, ban và join lại bị từ chối

File này mô tả ban một member (kèm lý do), lan truyền event tới các service, rồi người bị ban thử join lại qua invite (WF-12, FR-036, FR-037, FR-039). Kick giống ban nhưng không ghi `server_bans`, phát `MemberKicked`, và người bị kick join lại được.

```mermaid
sequenceDiagram
    autonumber
    actor M as Moderator
    actor B as User B
    participant GW as api-gateway
    participant CM as community-service
    participant CDB as Postgres community
    participant MQ as RabbitMQ community.events
    participant MS as messaging-service
    participant NT as notification-service
    participant PR as presence-service
    participant RT as realtime-gateway

    M->>GW: PUT /api/v1/servers/S/bans/B reason spam
    GW->>CM: chuyển tiếp, Bearer JWT
    CM->>CM: có BAN_MEMBERS, RoleHierarchyPolicy B thấp hơn M, B không phải owner
    CM->>CDB: BEGIN, pg_advisory_xact_lock theo cặp S và B
    CM->>CDB: INSERT server_bans ON CONFLICT DO NOTHING
    CM->>CDB: DELETE members S, B, member_roles xóa theo cascade
    CM->>CDB: INSERT outbox MemberBanned wasMember true, COMMIT
    CM-->>M: 204

    CM->>MQ: relay publish member.banned
    par các consumer phản ứng độc lập
        MQ->>MS: MemberBanned
        MS->>MS: INCR perm:ver:S, cache quyền cũ hết hiệu lực
    and
        MQ->>NT: MemberBanned
        NT->>NT: xóa server_members và read_states của B trong S
    and
        MQ->>PR: MemberBanned
        PR->>PR: bỏ S khỏi projection quan hệ của B
    and
        MQ->>RT: MemberBanned
        RT->>RT: hủy subscription của B ở /topic/servers/S và các kênh của S
        RT->>B: /user/queue/events MemberBanned
    end

    B->>GW: POST /api/v1/invites/code/accept
    GW->>CM: chuyển tiếp
    CM->>CDB: BEGIN, pg_advisory_xact_lock theo cặp S và B
    CM->>CDB: SELECT server_bans S, B
    CM->>CDB: ROLLBACK
    CM-->>B: 403 BANNED_FROM_SERVER

    M->>GW: DELETE /api/v1/servers/S/bans/B
    GW->>CM: chuyển tiếp
    CM->>CDB: xóa server_bans, outbox MemberUnbanned
    CM-->>M: 204, B có thể join lại bằng invite
```
