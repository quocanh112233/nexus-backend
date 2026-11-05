# Architecture Decision Records

Thư mục này chứa ADR ngắn cho các quyết định kiến trúc chính. Mỗi ADR: Bối cảnh, Quyết định, Hệ quả, Trạng thái. Trạng thái: "Accepted" = người dùng đã chấp thuận; "chờ duyệt" = còn chờ.

| ADR | Tiêu đề | Trạng thái | Mã quyết định |
|-----|---------|-----------|---------------|
| [ADR-001](ADR-001-message-broker.md) | Chọn RabbitMQ làm message broker | Accepted | P-01 |
| [ADR-002](ADR-002-grpc-sync.md) | gRPC cho giao tiếp đồng bộ nội bộ | Accepted (P-11 vị trí proto: chờ duyệt) | D-08, P-10, P-11 |
| [ADR-003](ADR-003-schema-per-service.md) | Một Postgres, schema + user riêng mỗi service | Accepted | D-04, D-05 |
| [ADR-004](ADR-004-cloudinary-signed-upload.md) | Upload trực tiếp Cloudinary bằng signed upload | Accepted | D-06 |
| [ADR-005](ADR-005-repo-structure.md) | Hai repo: backend monorepo + frontend | Accepted | P-02, P-26 |
| [ADR-006](ADR-006-build-tool.md) | Maven multi-module | Accepted | P-03 |
| [ADR-007](ADR-007-docker-packaging.md) | Chiến lược đóng gói Docker | Accepted (chi tiết P-19, P-32 chờ duyệt) | P-19, P-32 |
| [ADR-008](ADR-008-no-lombok.md) | Không dùng Lombok (Lombok là gì, dùng `record` thay thế) | Accepted | P-33 |
| [ADR-009](ADR-009-dao-layer.md) | Lớp DAO + mapper tách domain khỏi persistence | Accepted | D-21 |

Mẫu cho ADR mới: copy một file, tăng số, cập nhật bảng trên.
