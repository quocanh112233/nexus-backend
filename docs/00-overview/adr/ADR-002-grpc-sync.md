# ADR-002: gRPC cho giao tiếp đồng bộ nội bộ

Ghi lại lý do dùng gRPC (không phải REST) giữa các service khi cần request/response.

- **Trạng thái:** Accepted (D-08; thư viện P-10 người dùng duyệt). Vị trí proto P-11: vẫn chờ duyệt

## Bối cảnh
- Một số luồng cần câu trả lời ngay để quyết định (ví dụ: có quyền gửi tin không).
- Người dùng muốn học cách service giao tiếp; gRPC là kỹ năng backend được đánh giá cao.

## Quyết định
- Đồng bộ nội bộ dùng **gRPC** (HTTP/2, protobuf). REST chỉ ở biên (frontend → API Gateway).
- Proto tập trung tại `nexus-backend/proto/` (module `nexus-proto`), package `nexus.<context>.v1`.
- Thư viện: Spring gRPC; sinh code bằng `io.github.ascopes:protobuf-maven-plugin`; `buf lint` + `buf breaking` trong CI.
- Mọi lời gọi có **deadline**; retry chỉ cho RPC đọc, chỉ với `UNAVAILABLE`; có circuit breaker.
Chi tiết: [grpc-design.md](../../02-architecture/grpc-design.md).

## Hệ quả
- (+) Hợp đồng có kiểu, sinh code client/server, nhanh, dễ phát hiện breaking change.
- (+) Học được deadline propagation, status code, interceptor, metadata.
- (-) Khó debug bằng curl hơn REST (dùng `grpcurl`, bật server reflection ở local).
- (-) Coupling thời gian chạy: callee chết thì caller lỗi → bắt buộc timeout + fallback rõ ràng.
- Quy tắc: chỉ dùng gRPC khi caller **cần câu trả lời để tiếp tục**; còn lại dùng event.
