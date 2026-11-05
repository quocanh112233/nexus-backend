# Activity: luồng đăng ký

File này mô tả các bước và nhánh lỗi của luồng đăng ký (WF-01, FR-001).

```mermaid
flowchart TD
    A(["Khách mở trang đăng ký"]) --> B["Nhập email, username, password"]
    B --> C{"Frontend validate bằng zod hợp lệ?"}
    C -- "Không" --> B
    C -- "Có" --> D["POST /api/v1/auth/register"]
    D --> E{"Vượt rate limit?"}
    E -- "Có" --> E1["429 RATE_LIMITED"] --> B
    E -- "Không" --> F{"Backend validate DTO và value object?"}
    F -- "Không" --> F1["400 VALIDATION_FAILED"] --> B
    F -- "Có" --> G["Chuẩn hóa email, username về chữ thường"]
    G --> H["Hash password BCrypt"]
    H --> I["BEGIN: INSERT users, INSERT outbox UserRegistered"]
    I --> J{"Vi phạm unique index?"}
    J -- "email" --> J1["ROLLBACK, 409 EMAIL_TAKEN"] --> B
    J -- "username" --> J2["ROLLBACK, 409 USERNAME_TAKEN"] --> B
    J -- "Không" --> K["COMMIT, 201 public profile"]
    K --> L["Frontend tự gọi đăng nhập"]
    L --> M(["Vào trang chính"])
    K -.-> N["Outbox relay phát UserRegistered"]
```

Ghi chú: không kiểm tra trùng bằng `SELECT` trước rồi mới `INSERT` (có race condition); dựa vào **unique index** và dịch lỗi vi phạm thành `409`.
