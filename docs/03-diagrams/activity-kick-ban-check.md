# Activity: kiểm tra trước khi kick / ban

File này mô tả các kiểm tra của Community khi nhận yêu cầu kick hoặc ban (FR-036, FR-037) — áp dụng luật thứ bậc trong [permission-model.md](../01-requirements/permission-model.md#2-role).

```mermaid
flowchart TD
    A(["Kick hoặc ban: actor A, target T, server S"]) --> B{"A là member của S?"}
    B -- "Không" --> X404["404 NOT_FOUND"]
    B -- "Có" --> C{"Hành động là kick hay ban?"}
    C -- "Kick" --> D1{"A có KICK_MEMBERS hoặc là owner?"}
    C -- "Ban" --> D2{"A có BAN_MEMBERS hoặc là owner?"}
    D1 -- "Không" --> X403["403 PERMISSION_DENIED"]
    D2 -- "Không" --> X403
    D1 -- "Có" --> E1{"T đang là member của S?"}
    D2 -- "Có" --> E2{"T là owner hoặc chính A?"}
    E1 -- "Không" --> X404
    E1 -- "Có" --> E2
    E2 -- "Có" --> X403
    E2 -- "Không" --> F{"A là owner?"}
    F -- "Có" --> OK
    F -- "Không" --> G{"T là member và role cao nhất của T thấp hơn role cao nhất của A?"}
    G -- "T không là member, chỉ có ở ban" --> OK
    G -- "Có" --> OK
    G -- "Không" --> X403
    OK["Khóa advisory theo cặp S, T rồi thực hiện trong 1 transaction"] --> H{"Kick hay ban?"}
    H -- "Kick" --> K["DELETE members, outbox MemberKicked"]
    H -- "Ban" --> L["UPSERT server_bans, DELETE members nếu có, outbox MemberBanned"]
    K --> Z(["204"])
    L --> Z
```
