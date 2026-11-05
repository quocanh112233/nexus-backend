# Activity: kiểm tra permission khi gửi tin

File này mô tả quyết định ở Messaging khi nhận yêu cầu gửi tin (WF-05) và thuật toán tính quyền ở Community ([permission-model.md](../01-requirements/permission-model.md)).

## 1. Phía Messaging

```mermaid
flowchart TD
    A(["POST /api/v1/channels/C/messages"]) --> B{"channel_refs có C?"}
    B -- "Không" --> B1["gRPC CheckChannelPermissions để biết server_id"]
    B1 --> B2{"NOT_FOUND?"}
    B2 -- "Có" --> X404["404 NOT_FOUND"]
    B2 -- "Không" --> B3["Ghi channel_refs kind SERVER"] --> G
    B -- "Có, đã xóa" --> X404
    B -- "Có, kind DM" --> D{"Là participant?"}
    D -- "Không" --> X404
    D -- "Có" --> D1{"Hai người còn là bạn?"}
    D1 -- "Không" --> X403["403 PERMISSION_DENIED"]
    D1 -- "Có" --> OK
    B -- "Có, kind SERVER" --> C{"Cache Redis có quyền?"}
    C -- "Có" --> G
    C -- "Không" --> E["gRPC CheckChannelPermissions, deadline 300ms"]
    E --> F{"Kết quả gRPC"}
    F -- "OK" --> F1["Lưu cache 60s"] --> G
    F -- "DEADLINE_EXCEEDED hoặc UNAVAILABLE hoặc circuit mở" --> X503["503 DEPENDENCY_UNAVAILABLE"]
    G{"Có VIEW_CHANNEL và SEND_MESSAGES?"}
    G -- "Không" --> X403
    G -- "Có" --> H{"Có attachment?"}
    H -- "Có" --> H1{"Có ATTACH_FILES?"}
    H1 -- "Không" --> X403
    H1 -- "Có" --> OK
    H -- "Không" --> OK
    OK(["Lưu tin nhắn"])
```

## 2. Phía Community: tính quyền hiệu lực

```mermaid
flowchart TD
    S(["CheckChannelPermissions user U, channel C"]) --> A{"Kênh C tồn tại?"}
    A -- "Không" --> NF["NOT_FOUND"]
    A -- "Có" --> B{"U là member của server?"}
    B -- "Không" --> EMPTY["OK, granted rỗng"]
    B -- "Có" --> C{"U là owner?"}
    C -- "Có" --> ALL["OK, mọi quyền"]
    C -- "Không" --> D["base = quyền @everyone OR quyền các role của U"]
    D --> E{"base có ADMINISTRATOR?"}
    E -- "Có" --> ALL
    E -- "Không" --> F["Áp overwrite @everyone của kênh"]
    F --> G["Áp gộp overwrite các role của U: deny trước, allow sau"]
    G --> H["Áp overwrite riêng member U"]
    H --> I{"Có VIEW_CHANNEL?"}
    I -- "Không" --> EMPTY
    I -- "Có" --> J["Nếu thiếu SEND_MESSAGES thì bỏ ATTACH_FILES"]
    J --> R["OK, granted = tập quyền kênh"]
```
