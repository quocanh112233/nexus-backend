# Thiết kế gRPC (giao tiếp đồng bộ nội bộ)

File này quy định cách các service gọi nhau đồng bộ bằng gRPC: vị trí proto, quy ước đặt tên, versioning, deadline, retry, lỗi, metadata, thư viện và cách test. Quyết định nền: [ADR-002](../00-overview/adr/ADR-002-grpc-sync.md).

## 1. Khi nào dùng gRPC

Chỉ khi caller **cần câu trả lời để tiếp tục** và đó là **truy vấn** (không đổi dữ liệu callee). Danh sách đầy đủ trong MVP:

| RPC | Server | Client | Dùng trong luồng | Deadline |
|-----|--------|--------|------------------|----------|
| `community.v1.PermissionService/CheckChannelPermissions` | community | messaging | Gửi/sửa/xóa tin, reaction, lịch sử, chữ ký đính kèm (WF-05, WF-06, WF-09) | 300 ms |
| `community.v1.PermissionService/CheckServerMembership` | community | realtime-gateway | SUBSCRIBE `/topic/servers/{serverId}` | 300 ms |
| `messaging.v1.ChannelAccessService/CheckChannelAccess` | messaging | realtime-gateway | SUBSCRIBE `/topic/channels/{channelId}`, gửi typing | 500 ms (có thể gọi tiếp Community) |
| `identity.v1.UserService/GetUser` | identity | messaging | Gửi lời mời kết bạn (WF-07) | 300 ms |
| `identity.v1.UserService/BatchGetUsers` | identity | (dự phòng, chưa dùng ở MVP) | | 500 ms |
| `community.v1.PermissionService/ListServerIdsForUser` | community | presence | Nạp bù projection quan hệ (tập server của user) khi chưa có hoặc hết hạn (WF-10). **Hoãn sang P6** (thêm RPC là thay đổi tương thích); P4 chưa có RPC này trong proto | 500 ms |
| `messaging.v1.SocialService/ListFriendIds` | messaging | presence | Nạp bù projection quan hệ (tập bạn của user) (WF-10) | 500 ms |

Kick/ban **không cần RPC**: kiểm tra ban khi join nằm trong Community (P-35).

Đồ thị gọi là DAG (không vòng): `realtime-gateway → messaging → community`, `realtime-gateway → community`, `messaging → identity`, `presence → community`, `presence → messaging`. Không service nào gọi ngược lại presence hay realtime-gateway.

## 2. Vị trí proto và sinh code

```text
nexus-backend/
└── proto/                                  # module Maven: nexus-proto
    ├── pom.xml                             # protobuf-maven-plugin (ascopes) sinh Java + gRPC stub
    ├── buf.yaml                            # cấu hình buf lint + breaking
    └── src/main/proto/nexus/
        ├── common/v1/errors.proto          # ErrorInfo reason code dùng chung
        ├── identity/v1/user_service.proto
        ├── community/v1/permission_service.proto
        ├── messaging/v1/channel_access_service.proto
        └── messaging/v1/social_service.proto
```

- `mvn -pl proto install` sinh code vào `proto/target/generated-sources/` → đóng thành jar `nexus-proto`. Service khai báo dependency `com.nexus:nexus-proto`. **Không commit code sinh ra.**
- Class sinh ra chỉ được dùng trong `infrastructure/grpc/` (client) và `interfaces/grpc/` (server) của service; ArchUnit chặn `domain`/`application` import `com.nexus.proto..`.

## 3. Quy ước đặt tên

| Thành phần | Quy ước | Ví dụ |
|-----------|---------|-------|
| Package | `nexus.<context>.v<major>` | `nexus.community.v1` |
| `java_package` | `com.nexus.proto.<context>.v<major>` | `com.nexus.proto.community.v1` |
| File | `<snake_case_service>.proto` | `permission_service.proto` |
| Service | `<Danh từ>Service` | `PermissionService` |
| RPC | Động từ + danh từ, PascalCase | `CheckChannelPermissions` |
| Message | `<Rpc>Request` / `<Rpc>Response` (mỗi RPC một cặp riêng, không dùng chung) | `CheckChannelPermissionsRequest` |
| Field | `snake_case`; id là `string` (UUID) | `user_id`, `channel_id` |
| Enum | Giá trị có tiền tố tên enum, giá trị 0 là `_UNSPECIFIED` | `PERMISSION_UNSPECIFIED`, `PERMISSION_SEND_MESSAGES` |

Ví dụ:

```protobuf
syntax = "proto3";
package nexus.community.v1;
option java_multiple_files = true;
option java_package = "com.nexus.proto.community.v1";

// Trả lời câu hỏi quyền và tư cách thành viên, nguồn sự thật là Community
service PermissionService {
  rpc CheckChannelPermissions(CheckChannelPermissionsRequest) returns (CheckChannelPermissionsResponse);
  rpc CheckServerMembership(CheckServerMembershipRequest) returns (CheckServerMembershipResponse);
  // P6 thêm: rpc ListServerIdsForUser(ListServerIdsForUserRequest) returns (ListServerIdsForUserResponse);
}

enum Permission {
  PERMISSION_UNSPECIFIED = 0;
  PERMISSION_VIEW_CHANNEL = 1;
  PERMISSION_SEND_MESSAGES = 2;
  PERMISSION_READ_MESSAGE_HISTORY = 3;
  PERMISSION_ATTACH_FILES = 4;
  PERMISSION_ADD_REACTIONS = 5;
  PERMISSION_MANAGE_MESSAGES = 6;
}

message CheckChannelPermissionsRequest {
  string user_id = 1;
  string channel_id = 2;
}

message CheckChannelPermissionsResponse {
  string server_id = 1;
  repeated Permission granted = 2;
}

message CheckServerMembershipRequest {
  string user_id = 1;
  string server_id = 2;
}

message CheckServerMembershipResponse {
  bool is_member = 1;
}

// P6 thêm: ListServerIdsForUserRequest { string user_id = 1; } và ListServerIdsForUserResponse { repeated string server_ids = 1; }
```

P4 chỉ có `errors.proto`, `permission_service.proto` (2 RPC) và `user_service.proto`; `ChannelAccessService` (P6) và `SocialService` (P7) chưa có file proto.

File `messaging/v1/channel_access_service.proto`, `messaging/v1/social_service.proto` và `identity/v1/user_service.proto` (`BatchGetUsersRequest.user_ids` tối đa 100 phần tử; `granted` ở trên là các quyền kênh user có):

```protobuf
// Kiểm tra user có được xem một kênh (server hoặc DM) hay không
service ChannelAccessService {
  rpc CheckChannelAccess(CheckChannelAccessRequest) returns (CheckChannelAccessResponse);
}
message CheckChannelAccessRequest { string user_id = 1; string channel_id = 2; }
message CheckChannelAccessResponse { bool can_view = 1; string server_id = 2; bool is_dm = 3; }

// Cung cấp danh sách bạn bè cho service cần dựng projection quan hệ
service SocialService {
  rpc ListFriendIds(ListFriendIdsRequest) returns (ListFriendIdsResponse);
}
message ListFriendIdsRequest { string user_id = 1; }
message ListFriendIdsResponse { repeated string friend_ids = 1; }

// Tra cứu thông tin công khai của user cho service khác
service UserService {
  rpc GetUser(GetUserRequest) returns (GetUserResponse);
  rpc BatchGetUsers(BatchGetUsersRequest) returns (BatchGetUsersResponse);
}
message GetUserRequest { string user_id = 1; }
message GetUserResponse { User user = 1; }
message BatchGetUsersRequest { repeated string user_ids = 1; }
message BatchGetUsersResponse { repeated User users = 1; }
message User { string user_id = 1; string username = 2; string display_name = 3; string avatar_url = 4; }
```

Lý do `CheckChannelPermissions` trả **toàn bộ quyền kênh** thay vì hỏi từng quyền: một lần gọi phục vụ được nhiều hành động, cache một giá trị cho `(channelId, userId)`.

## 4. Versioning

- Thay đổi **tương thích** (thêm field mới, thêm RPC, thêm giá trị enum) → giữ `v1`.
- **Không bao giờ** đổi số field, đổi kiểu, xóa field (dùng `reserved`).
- Thay đổi phá vỡ → package `v2` mới, server phục vụ cả `v1` và `v2` cho tới khi mọi client chuyển xong.
- CI chạy `buf lint` và `buf breaking --against '.git#branch=main'` khi `proto/**` thay đổi.

## 5. Deadline, retry, circuit breaker

| Cơ chế | Quy tắc | Hiện thực |
|-------|---------|-----------|
| Deadline | **Bắt buộc** mỗi call (bảng mục 1). Deadline tự lan truyền: nếu realtime-gateway gọi Messaging với 500 ms, Messaging gọi Community với deadline còn lại (gRPC Context) | `stub.withDeadlineAfter(...)` trong client adapter |
| Retry | Chỉ RPC đọc; chỉ khi `UNAVAILABLE`; tối đa 2 lần, backoff 50 ms → 100 ms, có jitter; không retry `DEADLINE_EXCEEDED` (đã hết thời gian) | gRPC service config `retryPolicy` |
| Circuit breaker | Resilience4j, theo từng callee: cửa sổ 20 call, mở khi ≥50% lỗi, mở 10 s, half-open 3 call | Bọc quanh client adapter |
| Fallback | Kiểm tra quyền → **fail-closed** (trả 503, không cho gửi). Typing → bỏ qua | Ở `application` |
| Cache | Kết quả `CheckChannelPermissions` cache Redis (P-12) — giảm số call và giảm phụ thuộc runtime | `RedisChannelPermissionCache` |

## 6. Xử lý lỗi bằng status code

| Tình huống ở server | Status gRPC | Caller dịch thành (REST biên) |
|--------------------|------------|-------------------------------|
| Tham số sai (UUID hỏng) | `INVALID_ARGUMENT` | 500 (lỗi lập trình phía caller), log error |
| Kênh/user không tồn tại | `NOT_FOUND` | 404 |
| Server không tồn tại (với `CheckServerMembership`) | `OK` với `is_member=false` (không `NOT_FOUND`; cố ý, fail-closed) | caller coi như không phải member |
| Không phải member (với `CheckChannelPermissions`) | `OK` với `granted` rỗng | 403 |
| JWT trong metadata sai/hết hạn | `UNAUTHENTICATED` | 401 |
| Lỗi tạm thời, service đang khởi động | `UNAVAILABLE` | retry → 503 `DEPENDENCY_UNAVAILABLE` |
| Hết deadline | `DEADLINE_EXCEEDED` | 503 `DEPENDENCY_UNAVAILABLE` |
| Lỗi không mong đợi | `INTERNAL` | 503 |

- Chi tiết bổ sung qua `google.rpc.Status` + `ErrorInfo{reason, domain="nexus.community"}`; `reason` khớp `code` của lỗi REST ([api-conventions.md](api-conventions.md#4-định-dạng-lỗi)).
- "Không có quyền" là **câu trả lời hợp lệ** (`OK`), không phải lỗi.
- Quy tắc ở REST của messaging: user **không có `VIEW_CHANNEL`** (hoặc không phải member) → `404` (không lộ kênh có tồn tại); có `VIEW_CHANNEL` nhưng thiếu quyền của hành động → `403 PERMISSION_DENIED`.
- Server không bao giờ trả stack trace trong message.

## 7. Metadata

| Key | Nội dung | Ai đặt |
|-----|---------|--------|
| `authorization` | `Bearer <JWT gốc của user>` — callee verify như REST | Client interceptor lấy từ SecurityContext |
| `x-request-id` | Id request từ API Gateway | Client interceptor |
| `traceparent` | W3C trace context | OpenTelemetry instrumentation tự động |
| `x-caller-service` | Tên service gọi (log, metric) | Client interceptor |

Call kiểm tra khi SUBSCRIBE (realtime-gateway) vẫn chuyển JWT của user đã CONNECT. Call **không có user context** (presence gọi `ListServerIdsForUser`, `ListFriendIds` khi xử lý event) gửi metadata `x-service-token: <token riêng của presence>` thay cho `authorization`; server chỉ chấp nhận token này cho đúng các RPC trong allowlist của caller (P-36). mTLS là backlog BL-13. Server interceptor chung ở `libs/nexus-common-grpc`: xác thực JWT, đặt MDC (`requestId`, `userId`), map exception → status.

## 8. Thư viện

| Việc | Lựa chọn | Ghi chú |
|------|---------|---------|
| Server | `spring-boot-starter-grpc-server` (Boot 4.1.2 quản lý sẵn, spring-grpc 1.1.2; thay cho `spring-grpc-spring-boot-starter` của P-10) | Phương án thay thế: `net.devh:grpc-spring-boot-starter`. Boot tự đăng ký observation cho gRPC server (span, metric) và có interceptor OAuth2 riêng nên `libs/nexus-common-grpc` chặn bản trùng |
| Client | `ManagedChannelBuilder` dựng tay trong `infrastructure/grpc` | Retry bằng service config `retryPolicy`, circuit breaker Resilience4j; gắn `ObservationGrpcClientInterceptor` thủ công để lan truyền trace |
| Sinh code | `io.github.ascopes:protobuf-maven-plugin` | Tự tải `protoc` + plugin `grpc-java` |
| Lint / breaking | `buf` CLI (chạy trong CI bằng `bufbuild/buf-action`) | |
| Health | `grpc-services` (`grpc.health.v1`) | Compose healthcheck vẫn dùng HTTP actuator |
| Reflection | Bật ở profile `local` để dùng `grpcurl` | Tắt ở profile khác |
| Load balancing | Target `dns:///community-service:9092` + `round_robin` | Khi `--scale community-service=2` (P-23) |

## 9. Test

| Mức | Cách | Ví dụ |
|-----|------|-------|
| Unit server | Gọi trực tiếp class `PermissionGrpcService` với `StreamObserver` giả | Không quyền → `granted` rỗng |
| In-process | `grpc-testing` `InProcessServerBuilder` + client thật | Client adapter map `UNAVAILABLE` → `DependencyUnavailableException` |
| Integration | Testcontainers Postgres + server gRPC thật trên cổng ngẫu nhiên | Tính quyền với dữ liệu thật |
| Resilience | Client trỏ tới server cố tình chậm (sleep > deadline) | Trả 503 trong < 1 s, circuit breaker mở sau N lỗi |
| Hợp đồng | `buf breaking` trong CI | Xóa field → CI đỏ |
| Thủ công | `grpcurl -plaintext -H "authorization: Bearer $T" localhost:9092 nexus.community.v1.PermissionService/CheckChannelPermissions` | |
