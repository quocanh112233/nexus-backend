# Sequence: đăng nhập và làm mới token

File này mô tả luồng đăng nhập, gọi API bằng access token, refresh token rotation và phát hiện reuse (WF-02, FR-002..FR-004).

```mermaid
sequenceDiagram
    autonumber
    actor U as Người dùng
    participant FE as Frontend Next.js
    participant GW as api-gateway
    participant ID as identity-service
    participant DB as Postgres identity
    participant CM as community-service

    U->>FE: nhập email, password
    FE->>GW: POST /api/v1/auth/login
    GW->>GW: rate limit theo IP
    GW->>ID: chuyển tiếp
    ID->>DB: SELECT users WHERE lower(email)
    ID->>ID: BCrypt verify
    ID->>DB: INSERT refresh_tokens hash, family mới
    ID-->>GW: 200 accessToken, Set-Cookie nexus_rt
    GW-->>FE: 200
    FE->>FE: lưu accessToken trong bộ nhớ Zustand

    Note over FE,CM: Gọi API bình thường
    FE->>GW: GET /api/v1/servers, Bearer accessToken
    GW->>GW: verify JWT bằng JWKS đã cache
    GW->>CM: chuyển tiếp kèm Authorization
    CM->>CM: verify JWT lại
    CM-->>FE: 200 danh sách server

    Note over FE,ID: Access token hết hạn
    FE->>GW: GET /api/v1/servers
    GW-->>FE: 401 UNAUTHENTICATED
    FE->>GW: POST /api/v1/auth/refresh, cookie nexus_rt
    GW->>ID: chuyển tiếp
    ID->>DB: tìm token theo hash
    alt token hợp lệ, chưa revoke
        ID->>DB: revoke token cũ, INSERT token mới cùng family
        ID-->>FE: 200 accessToken mới, Set-Cookie mới
        FE->>GW: gọi lại request ban đầu
    else token đã revoke bị dùng lại
        ID->>DB: revoke toàn bộ family
        ID-->>FE: 401 REFRESH_TOKEN_REUSED
        FE->>U: chuyển về trang đăng nhập
    end
```

Ghi chú: frontend dùng một promise refresh chung để nhiều request 401 cùng lúc chỉ gọi refresh **một lần** (tránh tự kích hoạt reuse detection).
