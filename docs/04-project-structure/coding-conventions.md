# Coding conventions

File này quy định cách đặt tên, viết comment, viết test, commit và đặt tên nhánh cho code Nexus sau này. Quy ước comment là quyết định đã chốt (D-12).

## 1. Đặt tên

| Đối tượng | Quy ước | Ví dụ |
|----------|---------|-------|
| Package Java | `com.nexus.<service>.<layer>.<module>` | `com.nexus.messaging.domain.message` |
| Class | PascalCase, danh từ | `Message`, `PermissionCalculator` |
| Use case | `<Động từ><Danh từ>UseCase`, query: `<...>Query` | `SendMessageUseCase`, `GetHistoryQuery` |
| Port | Danh từ vai trò | `ChannelPermissionChecker`, `UploadSigner` |
| Adapter | `<Công nghệ><Port>` | `GrpcChannelPermissionChecker`, `CloudinaryUploadSigner` |
| Repository (domain) | `<Aggregate>Repository` (interface) | `MessageRepository` |
| Repository impl | `<Aggregate>RepositoryImpl` | `MessageRepositoryImpl` |
| DAO | `<Bảng/Entity>Dao` (interface Spring Data hoặc class dùng `JdbcClient`) | `MessageDao`, `OutboxEventDao` |
| Persistence entity | `<Tên>JpaEntity` | `MessageJpaEntity` |
| Mapper | `<Aggregate>Mapper` | `MessageMapper` |
| REST DTO | `<Hành động>Request` / `<Tài nguyên>Response` | `SendMessageRequest`, `MessageResponse` |
| Domain event | Thì quá khứ | `MessageCreated` |
| Listener | `<Producer>EventsListener` | `CommunityEventsListener` |
| Bảng/cột | `snake_case`, bảng số nhiều | `friend_requests.addressee_id` |
| Migration | `V<n>__<mo_ta_snake>.sql` | `V3__add_message_nonce.sql` |
| Proto | Xem [grpc-design.md](../02-architecture/grpc-design.md#3-quy-ước-đặt-tên) | |
| Exchange/queue/routing key | Xem [event-catalog.md](../02-architecture/event-catalog.md#1-quy-ước) | |
| TypeScript | file component PascalCase, còn lại camelCase; store `use<Domain>Store` | `MessageList.tsx`, `useMessagesStore` |
| Biến môi trường | `UPPER_SNAKE_CASE` | `COMMUNITY_GRPC_TARGET` |

## 2. Quy ước comment (đã chốt — D-12)

1. **Chỉ một dòng comment trước hàm, class, interface, abstract class**, ghi chức năng/vai trò của nó.
2. **Test phải có đủ 3 dòng comment**: dòng 1 là kịch bản, dòng 2 là input, dòng 3 là output mong đợi.
3. **Không để comment mồ côi** (comment không gắn với hàm/class/interface/abstract nào, comment code đã xóa, TODO lơ lửng).
4. **Sửa code đã có comment thì sửa comment đó**, không tạo thêm comment mới.

Ví dụ Java:

```java
// Tính tập quyền hiệu lực của một member tại một kênh theo role và overwrite
public final class PermissionCalculator {

    // Trả về quyền kênh sau khi áp dụng role, ADMINISTRATOR và overwrite theo thứ tự
    public PermissionSet compute(Member member, Server server, Channel channel) {
        ...
    }
}
```

Ví dụ test:

```java
// Kịch bản: member có role Mod được allow SEND_MESSAGES ở kênh mà @everyone bị deny
// Input: @everyone deny SEND_MESSAGES, role Mod allow SEND_MESSAGES, member có role Mod
// Output: tập quyền chứa SEND_MESSAGES
@Test
void modCanSendInAnnouncementChannel() { ... }
```

Ví dụ TypeScript:

```ts
// Gọi API kèm access token và tự refresh một lần khi nhận 401
export async function http<T>(path: string, init?: RequestInit): Promise<T> { ... }
```

Không áp dụng comment cho: getter/setter tự sinh, record đơn giản trong DTO (vẫn có 1 dòng trước record/class). Javadoc nhiều dòng **không dùng**.

## 3. Quy ước test

| Hạng mục | Quy ước |
|---------|---------|
| Tên class | `<ClassĐượcTest>Test` (unit), `<...>IT` (integration, chạy bởi Failsafe) |
| Tên method | camelCase mô tả hành vi: `rejectsSixthAttachment` |
| Cấu trúc | Given / When / Then trong thân (không comment thêm ngoài 3 dòng bắt buộc) |
| Assertion | AssertJ |
| Mock | Chỉ mock port (`application/port`), không mock domain |
| Dữ liệu | Builder trong `src/test/.../fixtures` |
| Integration | Testcontainers dùng chung qua `libs/nexus-test-support` (container reuse cho tốc độ) |
| Frontend | Vitest cho store/hàm thuần; Playwright cho e2e |

Chi tiết chiến lược: [testing-strategy.md](../05-plan/testing-strategy.md).

## 4. Code style

- Java: Spotless + google-java-format (CI fail nếu chưa format).
- **Không dùng Lombok** (đã chốt P-33, [ADR-008](../00-overview/adr/ADR-008-no-lombok.md)): `record` cho value object, DTO REST, DTO consumer, payload event; entity/aggregate viết constructor + getter tường minh, **không setter công khai** — đổi trạng thái qua method nghiệp vụ; class domain là `final`.
- Không `@Autowired` field; dùng constructor injection.
- Không trả `XxxJpaEntity` ra ngoài `infrastructure`; `application` không gọi DAO trực tiếp, chỉ gọi `XxxRepository` ([ADR-009](../00-overview/adr/ADR-009-dao-layer.md)).
- `@Transactional` chỉ đặt trên method của application service (use case).
- TypeScript: ESLint (next/core-web-vitals) + Prettier, `strict: true`.

## 5. Commit và branch (P-22)

| Hạng mục | Quy ước |
|---------|---------|
| Commit | Conventional Commits: `<type>(<scope>): <mô tả>`; type ∈ `feat, fix, refactor, test, docs, build, ci, chore, perf`; scope = tên service hoặc `proto`, `infra`, `libs` |
| Ví dụ | `feat(messaging): gửi tin nhắn kiểm tra quyền qua gRPC` |
| Branch | `main` luôn xanh; nhánh ngắn hạn `feat/<scope>-<mô-tả>`, `fix/...`, `docs/...` |
| PR | Một mục đích; CI xanh; checklist trong `pull_request_template.md` (cập nhật docs/event-catalog/proto nếu đổi hợp đồng, sửa đồng loạt Dockerfile nếu đổi mẫu) |
| Merge | Squash merge |
| Tag | `v<major>.<minor>.<patch>` khi xong mỗi phase (ví dụ `v0.4.0` sau P4) |
