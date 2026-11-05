# ADR-005: Hai repo — backend monorepo + frontend

Ghi lại cách tổ chức repository.

- **Trạng thái:** Accepted — người dùng duyệt (P-02, P-26)

## Bối cảnh
- Đã tồn tại `nexus-backend` và `nexus-frontend` trên GitHub (D-13).
- Thay đổi hợp đồng (proto, event) thường chạm nhiều service cùng lúc.

## Quyết định
- `nexus-backend`: monorepo chứa mọi service, gateway, `proto/`, `libs/`, `infra/`, `docs/` (P-26), workflow CI theo service với path filter.
- `nexus-frontend`: Next.js, CI riêng.
Cây thư mục: [repo-structure.md](../../04-project-structure/repo-structure.md).

## Hệ quả
- (+) Đổi proto + server + client trong một PR, CI kiểm tra breaking ngay.
- (+) Khớp thực tế repo đã có; frontend độc lập.
- (-) Cần path filter để không build lại mọi service mỗi commit.
- (-) Dễ "lén" coupling qua `libs/` → luật: `libs/` không chứa domain (xem [backend-structure.md](../../04-project-structure/backend-structure.md)).
