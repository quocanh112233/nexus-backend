# ADR-003: Một PostgreSQL, schema + user riêng mỗi service

Ghi lại cách hiện thực "database per service" với chi phí hạ tầng thấp.

- **Trạng thái:** Accepted (D-04, D-05)

## Bối cảnh
- Microservice cần sở hữu dữ liệu riêng; nhưng chạy 5 instance Postgres trên máy local là lãng phí.

## Quyết định
- Một instance PostgreSQL; schema `identity`, `community`, `messaging`, `notification`. Presence không có schema (chỉ Redis).
- Mỗi service có DB user riêng (`identity_svc`, ...) chỉ có quyền trên schema của mình (`REVOKE` mặc định trên `public`).
- Cấm query chéo schema và foreign key chéo schema. Dữ liệu service khác: gọi gRPC hoặc dựng projection từ event, chỉ lưu id.
- Mỗi service tự chạy Flyway khi khởi động, `flyway.schemas=<schema>`.

## Hệ quả
- (+) Ranh giới dữ liệu được DB *cưỡng chế* (sai quyền là lỗi ngay), không chỉ là quy ước.
- (+) Sau này tách ra instance riêng chỉ cần đổi connection string.
- (-) Chung instance → chung điểm lỗi và chung tài nguyên (chấp nhận cho học/demo).
- (-) Không join được dữ liệu giữa service → phải học projection và eventual consistency (đây là mục tiêu học).
