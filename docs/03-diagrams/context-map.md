# Context map

File này vẽ quan hệ DDD giữa các bounded context. Bảng giải thích: [bounded-contexts-and-context-map.md](../02-architecture/bounded-contexts-and-context-map.md). Ký hiệu nhãn: U = upstream, D = downstream, OHS = Open Host Service, PL = Published Language, CS = Customer-Supplier, ACL = Anti-corruption layer.

```mermaid
flowchart LR
    ID["Identity - Generic"]
    CM["Community - Core"]
    MS["Messaging - Core"]
    PR["Presence - Supporting"]
    NT["Notification - Supporting"]
    RT["realtime-gateway"]
    CLD["Cloudinary - bên ngoài"]

    ID -- "U: OHS, PL - JWT, gRPC UserService, identity.events" --> MS
    ID -- "U: OHS, PL - JWT" --> CM
    CM -- "U: CS, PL - gRPC PermissionService, community.events" --> MS
    CM -- "U: PL - community.events" --> NT
    MS -- "U: PL - messaging.events" --> NT
    CM -- "U: PL - gRPC, events" --> RT
    MS -- "U: PL - gRPC ChannelAccessService, events" --> RT
    RT -- "U: PL - realtime.events" --> PR
    CM -- "U: PL - gRPC ListServerIdsForUser, community.events" --> PR
    MS -- "U: PL - gRPC SocialService, messaging.events" --> PR
    PR -- "U: PL - presence.events" --> RT
    NT -- "U: PL - notification.events" --> RT
    CLD -- "U: ACL ở phía D" --> ID
    CLD -- "U: ACL ở phía D" --> MS
```
