# P3 — Role, permission & kick/ban Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thêm role, permission bitset, overwrite theo kênh, luật thứ bậc và kick/ban/gỡ ban vào `community-service` (FR-032..034, FR-036, FR-037, FR-039); thay mọi check "chỉ owner" của P2 bằng permission thật.

**Architecture:** Hai domain service thuần (`PermissionCalculator`, `RoleHierarchyPolicy`) không phụ thuộc Spring; role nằm trong aggregate `Server` (đổi role bump `servers.version` → hai sửa đồng thời ra `409`); `Member` giữ `roleIds`; `ServerBan` là aggregate mới; join/ban/gỡ ban tuần tự hóa theo cặp `(server, user)` bằng `pg_advisory_xact_lock`. Không đổi gateway (route `/api/v1/servers/**` đã phủ hết), không gRPC (P4).

**Tech Stack:** Như P2 (Java 21, Spring Boot 4.1.x, PostgreSQL 16 schema `community`, Flyway, Testcontainers, ArchUnit).

**Spec:** Không có spec riêng. Lập từ `docs/05-plan/backend-plan.md` mục P3, `docs/01-requirements/{permission-model,functional-requirements,workflows}.md` (FR-032..039, WF-08, WF-12), `docs/02-architecture/{services,event-catalog,data-model,api-conventions}.md`, `docs/03-diagrams/{activity-kick-ban-check,sequence-kick-ban}.md`, và plan P2 (`2026-11-05-p2-community-gateway.md`) cho khuôn mã.

## Global Constraints

- Kế thừa toàn bộ **Global Constraints của plan P2** (Java 21, không Lombok, 4 lớp + ArchUnit, DAO ADR-009, UUIDv7, `Clock` + `truncatedTo(MICROS)`, RFC 9457, 404 cho người ngoài / 403 cho thiếu quyền, máy dev có PostgreSQL riêng ở `127.0.0.1:5432` **không bao giờ** chạy migration lên đó, compose phải `POSTGRES_PORT=5433`).
- Comment: **1 dòng** trước class/hàm/constructor; **test đủ 3 dòng** (kịch bản / input / output); không comment mồ côi; sửa code có comment thì sửa comment. Sau `./mvnw spotless:apply` kiểm tra comment không bị ngắt dòng.
- Giao tiếp với người dùng bằng **tiếng Việt**. Caveman + ponytail chỉ áp dụng cho lời nói/report, không cho code, commit, tài liệu.
- Commit: người dùng **tự commit**; executor không chạy `git commit/tag/push`. Mỗi task kết thúc bằng message gợi ý Conventional Commits + `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Làm trên nhánh `feat/p3-role-permission` tách từ `feat/p2-community` **sau khi** người dùng commit bản sửa gateway (JWKS TTL, bucket chung) đang chờ.
- Permission = enum `Permission` đã có (13 bit, `ADMINISTRATOR` bit 0 … `BAN_MEMBERS` bit 12). **Phạm vi kênh** (overwrite áp dụng được): bit 2..10 (`MANAGE_ROLES … MANAGE_MESSAGES`). Chỉ phạm vi server: `ADMINISTRATOR, MANAGE_SERVER, KICK_MEMBERS, BAN_MEMBERS`.
- Owner luôn có mọi quyền và bỏ qua luật thứ bậc; `@everyone` là role `is_default`, `position = 0`, không xóa/đổi tên/đổi vị trí được; role khác `position` dày `1..n`, số lớn = cao hơn.
- Mã lỗi mới: `BANNED_FROM_SERVER` 403. Các mã khác dùng lại (`VALIDATION_FAILED, PERMISSION_DENIED, NOT_FOUND, VERSION_CONFLICT`). Id sai định dạng, bit permission ngoài tập định nghĩa, `allow ∩ deny ≠ ∅`, `reason` > 512 ký tự → `400`, không `500`.
- Event theo `event-catalog.md`; `aggregateType` = `Role`/`Member`/`Channel`/`Ban`; `aggregateId` = id role / `serverId` (Member*) / `channelId`; payload id là chuỗi UUID, `permissions` là chuỗi số thập phân (event-catalog.md:68).
- Migration: `V2__roles_position_deferrable.sql`, `V3__server_bans.sql`. Không sửa `V1`.

## Review Focus

1. **Ban đồng thời với join:** 50 vòng, mỗi vòng ban và accept invite của cùng một user chạy song song → không vòng nào kết thúc với user vừa là member vừa có dòng ban (Task 9).
2. **Vượt thứ bậc:** Mod ban/kick Admin hoặc người ngang cấp; ban owner; ban/kick chính mình; tự gán role cao hơn mình; cấp permission mình không có; chuyển role lên trên role cao nhất của mình; sửa `@everyone` name/position → mỗi ca đều bị chặn (Task 3, 4, 8).
3. **404 hay 403:** người ngoài server gọi mọi endpoint mới → `404`; member thiếu quyền → `403`; `DELETE /members/@me` vẫn là "rời server", không bị `/{userId}` nuốt (Task 3-8).
4. **Dữ liệu mồ côi:** xóa role → người giữ role về quyền `@everyone` và overwrite của role đó biến mất; bị kick/ban/rời rồi join lại → không còn role cũ, không còn overwrite MEMBER cũ (Task 4, 6, 8).
5. **Đầu vào xấu:** `permissions` âm hoặc có bit lạ, `allow`/`deny` chồng nhau, tên role 101 ký tự, `color` > `0xFFFFFF`, `position` ngoài `1..n`, `roleIds` trùng/lạ/chứa `@everyone`, `targetType` lạ, `reason` 513 ký tự, `cursor` rác → `400 VALIDATION_FAILED`; hai PATCH role đồng thời → một `409` (Task 3-8).

---

### Task 1: PermissionCalculator và RoleHierarchyPolicy (domain thuần)

**Files:**
- Create: `.../domain/channel/{ChannelOverwrite,OverwriteTarget}.java`, `.../domain/server/{PermissionCalculator,RoleHierarchyPolicy,Actor}.java`
- Modify: `.../domain/server/{Permission,PermissionSet}.java`
- Test: `.../domain/server/{PermissionCalculatorTest,RoleHierarchyPolicyTest,PermissionSetTest}.java`

**Interfaces:**
- Produces: `enum OverwriteTarget { ROLE, MEMBER }`; `record ChannelOverwrite(OverwriteTarget type, UUID targetId, PermissionSet allow, PermissionSet deny)`; `PermissionSet.CHANNEL_SCOPE` (bit 2..10) và `PermissionSet.isChannelScoped(Permission)`; `PermissionSet.contains(PermissionSet other)` (other ⊆ this), `union(PermissionSet)`, `minus(PermissionSet)`, `intersects(PermissionSet)`, `static PermissionSet ofBits(long)` ném `DomainValidationException` nếu bit âm hoặc ngoài `ALL`.
- `Actor(UserId userId, boolean owner, PermissionSet serverPermissions, int highestPosition)` + `static Actor of(Server server, Member member)` (owner → `ALL`; `highestPosition` = max position các role của member kể cả `@everyone` = 0).
- `PermissionCalculator` (final, static): `PermissionSet server(Server, Member)` — owner → `ALL`; base = `@everyone` ∪ role của member; có `ADMINISTRATOR` → `ALL`. `PermissionSet channel(Server, Member, List<ChannelOverwrite>)` — đúng 7 bước ở `permission-model.md` §4; overwrite chỉ tác động bit `CHANNEL_SCOPE`; thiếu `VIEW_CHANNEL` → rỗng; thiếu `SEND_MESSAGES` → bỏ `ATTACH_FILES`.
- `RoleHierarchyPolicy` (final, static): `canManageRole(Actor, Role)` = owner ∨ `role.position < actor.highestPosition`; `canPlaceAt(Actor, int position, boolean insertingNew)` = owner ∨ (insertingNew ? `position ≤ highest` : `position < highest`); `canGrant(Actor, PermissionSet changedBits)` = owner ∨ `actor.serverPermissions.contains(changedBits)`; `canModerate(Actor actor, UserId target, boolean targetIsOwner, Integer targetHighestOrNull)` — false nếu target là owner hoặc chính actor; actor owner → true; target không phải member (`null`) → true; ngược lại `targetHighest < actor.highestPosition`.
- `Member` chưa đổi ở task này; test dựng `Actor` trực tiếp.

- [ ] **Step 1: Viết test** — `PermissionCalculatorTest` dạng bảng ≥ 15 ca, mỗi bước thuật toán ít nhất một ca và đủ 4 ví dụ ở `permission-model.md` §7: owner → `ALL`; admin vẫn thấy kênh bị deny `VIEW_CHANNEL`; overwrite `@everyone` deny `SEND_MESSAGES` + role Mod allow → Mod gửi được, member thường không; MEMBER overwrite deny thắng role allow; thiếu `VIEW_CHANNEL` → `0`; thiếu `SEND_MESSAGES` → mất `ATTACH_FILES`; deny trên bit phạm vi server bị bỏ qua; nhiều role gộp `deny` rồi `allow`. `RoleHierarchyPolicyTest`: mỗi hàm đủ ca owner / ngang cấp / thấp hơn / cao hơn, `canModerate` đủ ca owner-target, self, non-member, ngang cấp. `PermissionSetTest` thêm `ofBits` từ chối âm/bit lạ.
- [ ] **Step 2: Chạy** `./mvnw -B -o -pl services/community-service test -Dtest='PermissionCalculatorTest,RoleHierarchyPolicyTest,PermissionSetTest'` → Expected: FAIL (lớp chưa có).
- [ ] **Step 3: Implement** theo Interfaces; thuật toán `channel` viết thẳng theo §4, không thêm bước.
- [ ] **Step 4: Chạy lại** → PASS; `./mvnw -B -o spotless:check`.
- [ ] **Step 5: Dừng để người dùng commit** — `feat(community): PermissionCalculator và RoleHierarchyPolicy`.

### Task 2: Role trong aggregate Server, roleIds của Member, persistence, event

**Files:**
- Create: `.../domain/server/{RoleChanges,RoleCreated,RoleUpdated,RoleDeleted}.java`, `.../domain/member/MemberRolesChanged.java`, `resources/db/migration/V2__roles_position_deferrable.sql`, `.../infrastructure/persistence/member/{MemberRoleDao,MemberRoleJpaEntity}.java`
- Modify: `.../domain/server/Server.java`, `.../domain/member/{Member,MemberRepository}.java`, `.../infrastructure/persistence/server/{ServerRepositoryImpl,ServerMapper}.java`, `.../infrastructure/persistence/member/MemberRepositoryImpl.java`, `.../infrastructure/messaging/OutboxDomainEventPublisher.java`
- Test: `.../domain/server/ServerRolesTest.java`, `.../domain/member/MemberTest.java`, `.../infrastructure/persistence/server/ServerRepositoryImplIT.java`, `.../infrastructure/persistence/member/MemberRepositoryImplIT.java`, `.../infrastructure/messaging/OutboxDomainEventPublisherTest.java`

**Interfaces:**
- Produces trên `Server`: `Role createRole(String name, int color, PermissionSet permissions, Integer positionOrNull, Instant now)` (null → vị trí 1; chèn và đẩy các role ≥ vị trí lên, đánh số lại dày; phát `RoleCreated`); `void updateRole(RoleId id, RoleChanges changes, Instant now)` (`RoleChanges(String name, Integer color, PermissionSet permissions, Integer position)` mọi trường nullable; `@everyone` đổi name/position → `DomainValidationException`; position ngoài `1..n` → `DomainValidationException`; đổi vị trí = gỡ rồi chèn lại và đánh số dày; mọi thay đổi gán `updatedAt = now` để `version` tăng; phát tối đa một `RoleUpdated`); `removeRole(RoleId, Instant now)` (sửa hàm P2: đánh số lại dày, phát `RoleDeleted`); `Optional<Role> findRole(RoleId)`; `Role defaultRole()`. Tên role 1..100 sau trim, `color` 0..16777215.
- Events (record, `implements DomainEvent`): `RoleCreated/RoleUpdated(ServerId, RoleId, String name, PermissionSet permissions, int position)`, `RoleDeleted(ServerId, RoleId)`, `MemberRolesChanged(ServerId, UserId, List<RoleId> roleIds)`. Outbox: routing key `role.created`, `role.updated`, `role.deleted`, `member.roles-changed` (payload khớp `event-catalog.md`; `permissions` là số).
- `Member`: thêm field `List<RoleId> roleIds` (không gồm `@everyone`), `Member.rehydrate(ServerId, UserId, Instant joinedAt, List<RoleId> roleIds)` (thay chữ ký cũ, sửa mọi chỗ gọi), `void replaceRoles(Collection<RoleId> roleIds)` (so sánh dạng tập; khác thì phát `MemberRolesChanged` với danh sách mới, bằng nhau thì không làm gì). `MemberRepository` thêm `void save(Member)` (ghi lại `member_roles` bằng cách xóa-chèn trong transaction của use case); `find`/`page` nạp `roleIds` bằng một query `MemberRoleDao.findAllByServerIdAndUserIdIn`.
- `V2__roles_position_deferrable.sql`: bỏ `ux_roles_server_position`, tạo lại `UNIQUE (server_id, position) DEFERRABLE INITIALLY DEFERRED` để đổi chỗ nhiều role trong một transaction.
- `ServerRepositoryImpl.save` cho server đã tồn tại: sau `saveAndFlush` hàng server, đồng bộ `roles` (xóa role không còn, upsert phần còn lại) trong cùng transaction.

- [ ] **Step 1: Viết test** — `ServerRolesTest`: tạo role mặc định ở vị trí 1 và đẩy role cũ lên; chèn giữa; `updateRole` đổi vị trí giữ thứ tự dày; `@everyone` đổi tên/vị trí bị từ chối; `removeRole` đánh số lại; mỗi thao tác phát đúng một event. `MemberTest.replaceRoles` (đổi → 1 event, cùng tập khác thứ tự → 0 event). `ServerRepositoryImplIT`: lưu server, thêm 3 role, đổi chỗ hai role trong một lần `save` (không vi phạm unique), xóa một role, tải lại đúng; hai bản sao cùng version cùng sửa role → bản sau `OptimisticLockingFailureException`. `MemberRepositoryImplIT`: `save` rồi `find`/`page` trả `roleIds`; xóa role (qua `ServerRepository`) làm biến mất role khỏi `roleIds`. `OutboxDomainEventPublisherTest` thêm 4 event.
- [ ] **Step 2: Chạy** các test trên → FAIL.
- [ ] **Step 3: Implement** theo Interfaces.
- [ ] **Step 4: Chạy lại** `./mvnw -B -o -pl services/community-service verify -Dtest='ServerRolesTest,MemberTest,OutboxDomainEventPublisherTest' -Dit.test='ServerRepositoryImplIT,MemberRepositoryImplIT'` → PASS; toàn bộ test cũ của community vẫn xanh (`MemberIT`, `ServerControllerIT`…).
- [ ] **Step 5: Dừng để người dùng commit** — `feat(community): role trong aggregate Server, roleIds của Member, event role`.

### Task 3: Role CRUD (FR-032) và `ServerAccess.requirePermission`

**Files:**
- Create: `.../application/server/{Authorized,CreateRoleUseCase,UpdateRoleUseCase,DeleteRoleUseCase,ListRolesQuery}.java`, `.../interfaces/rest/{RoleController,RoleResponse,CreateRoleRequest,UpdateRoleRequest}.java`
- Modify: `.../application/server/{ServerAccess,RoleView}.java`
- Test: `.../application/server/ServerAccessTest.java`, `.../interfaces/rest/RoleIT.java`

**Interfaces:**
- Consumes: Task 1 (`Actor`, policy, calculator), Task 2 (`Server.createRole/updateRole/removeRole`).
- Produces: `record Authorized(Server server, Member member, Actor actor)`; `ServerAccess.requirePermission(ServerId, UserId, Permission) → Authorized` — server/member không có → `ServerNotFoundException` (404); thiếu quyền (theo `PermissionCalculator.server`) → `PermissionDeniedException` (403). `requireMember`, `requireOwner` giữ nguyên.
- REST (`/api/v1/servers/{serverId}/roles`): `GET` (mọi member) → `200` mảng `RoleResponse(id, name, color, permissions:long, position, isDefault)` sắp `position` tăng; `POST {name, color?=0, permissions?=0, position?}` → `201`; `PATCH /{roleId} {name?, color?, permissions?, position?}` → `200`; `DELETE /{roleId}` → `204` (`@everyone` → `400`). Cần `MANAGE_ROLES`; role đích `canManageRole`; `permissions` mới/đổi: bit thay đổi (`old XOR new`) phải `canGrant`; vị trí đích `canPlaceAt` (POST: `insertingNew=true`, PATCH: `false`); bit ngoài `ALL`/âm → `400`; thiếu một điều kiện thứ bậc → `403`. Role không thuộc server → `404`.

- [ ] **Step 1: Viết test** — `ServerAccessTest` (người ngoài → 404, member thiếu quyền → 403, role cấp quyền → qua, `ADMINISTRATOR` → qua mọi quyền, owner → qua). `RoleIT` (Testcontainers + JWT như `ChannelIT`): owner tạo role `Mod` (`KICK_MEMBERS|BAN_MEMBERS`) → 201; member thường tạo → 403; người ngoài → 404; actor có role `Lead` (position 2, `MANAGE_ROLES`) sửa/xóa role position ≥ 2 → 403 và position 1 → 200; không cấp được bit mình không có; chuyển role lên ≥ vị trí role cao nhất của mình → 403; `PATCH @everyone` đổi `name`/`position` → 400, đổi `permissions` được; `DELETE @everyone` → 400; tên 101 ký tự / `color` 16777216 / `permissions=-1` / bit lạ / `position=0` → 400; hai `PATCH` song song cùng role (thread song song, cùng server) → đúng một `409 VERSION_CONFLICT`.
- [ ] **Step 2: Chạy** `RoleIT` → FAIL.
- [ ] **Step 3: Implement** use case `@Transactional` đọc `Authorized`, gọi `Server` rồi `serverRepository.save`, publish `server.pullEvents()`.
- [ ] **Step 4: Chạy lại** → PASS.
- [ ] **Step 5: Dừng để người dùng commit** — `feat(community): CRUD role với luật thứ bậc FR-032`.

### Task 4: Gán role cho member (FR-033)

**Files:**
- Create: `.../application/member/AssignRolesUseCase.java`, `.../interfaces/rest/AssignRolesRequest.java`
- Modify: `.../interfaces/rest/{MemberController,MemberResponse}.java`
- Test: `.../interfaces/rest/MemberRolesIT.java`

**Interfaces:**
- Consumes: `ServerAccess.requirePermission(…, MANAGE_ROLES)`, `Member.replaceRoles`, `MemberRepository.save`, `RoleHierarchyPolicy.canManageRole`.
- Produces: `PUT /api/v1/servers/{serverId}/members/{userId}/roles` body `{roleIds: [uuid]}` → `200 MemberResponse(userId, joinedAt, roleIds)`. Target không phải member → `404`. `roleIds` trùng, không thuộc server, hoặc là `@everyone` → `400`. Mọi role trong **hiệu đối xứng** (thêm hoặc bớt) phải `canManageRole`, ngược lại `403`; tự gán cho chính mình cũng theo luật đó. Không đổi gì → `200`, không phát event. Có đổi → `MemberRolesChanged` vào outbox cùng transaction.

- [ ] **Step 1: Viết test** `MemberRolesIT`: owner gán `Mod` cho B → 200, `GET /members` thấy `roleIds`; Lead (position 2) gán role position 1 → 200, gán role position 2 (ngang mình) hoặc 3 → 403, tự gán role 3 cho mình → 403; gỡ role cao hơn mình khỏi người khác → 403; roleIds `[]` gỡ hết; id lạ/trùng/`@everyone` → 400; target không phải member → 404; người ngoài → 404; gán cùng tập hai lần → lần hai không thêm dòng outbox; outbox có đúng một `MemberRolesChanged` sau lần đổi thật.
- [ ] **Step 2: Chạy** → FAIL. **Step 3: Implement.** **Step 4: Chạy lại** → PASS; thêm assert ở `RoleIT`: xóa role đang được gán → `roleIds` của người giữ hết role đó.
- [ ] **Step 5: Dừng để người dùng commit** — `feat(community): gán role cho member FR-033`.

### Task 5: Thay check "chỉ owner" bằng permission thật

**Files:**
- Modify: `.../application/server/RenameServerUseCase.java`, `.../application/channel/{Create,Update,Delete}CategoryUseCase.java` và `{Create,Update,Delete}ChannelUseCase.java`, `.../application/invite/{CreateInviteUseCase,ListInvitesQuery,RevokeInviteUseCase}.java`; sửa comment đầu mỗi lớp
- Test: cập nhật `ServerManagementIT, CategoryIT, ChannelIT, InviteIT`; thêm `.../interfaces/rest/PermissionEnforcementIT.java`

**Interfaces:**
- Consumes: `ServerAccess.requirePermission`. Ánh xạ: đổi tên server → `MANAGE_SERVER`; category/kênh tạo/sửa/xóa → `MANAGE_CHANNELS`; tạo invite → `CREATE_INVITE` (mức server); liệt kê invite → `MANAGE_SERVER`; thu hồi invite → người tạo **hoặc** `MANAGE_SERVER`. Xóa server vẫn chỉ owner (`requireOwner`, FR-022). `leave`, `list members`, `GET server` vẫn chỉ cần là member.

- [ ] **Step 1: Viết test** `PermissionEnforcementIT`: với mỗi endpoint trên, (a) member thường → 403 (trừ tạo invite: `@everyone` mặc định có `CREATE_INVITE` → 201); (b) member được gán role đúng quyền → thành công; (c) role có `ADMINISTRATOR` → thành công mọi endpoint; (d) người ngoài → 404; (e) gỡ `CREATE_INVITE` khỏi `@everyone` bằng `PATCH` role → member thường tạo invite 403; (f) hai `PATCH` kênh song song cùng kênh → đúng một `409 VERSION_CONFLICT` (backend-plan #6, `Server`/`Channel` đã có `version` từ P2 nhưng chưa có test ở tầng REST). Các IT cũ vẫn xanh.
- [ ] **Step 2: Chạy** → FAIL. **Step 3: Implement** (thay `requireOwner` bằng `requirePermission`). **Step 4: Chạy lại** toàn bộ IT của community → PASS.
- [ ] **Step 5: Dừng để người dùng commit** — `feat(community): thay check owner bằng permission MANAGE_*, CREATE_INVITE`.

### Task 6: Overwrite theo kênh (FR-034), `permissions/@me`, lọc kênh theo VIEW_CHANNEL

**Files:**
- Create: `.../domain/channel/{ChannelOverwriteRepository,ChannelPermissionsChanged}.java`, `.../application/channel/{SetChannelOverwriteUseCase,RemoveChannelOverwriteUseCase}.java`, `.../application/server/{MyPermissionsQuery,MyPermissions}.java`, `.../infrastructure/persistence/channel/{OverwriteDao,OverwriteJpaEntity,OverwriteKey,ChannelOverwriteRepositoryImpl}.java`, `.../interfaces/rest/{OverwriteController,OverwriteRequest,MyPermissionsResponse}.java`
- Modify: `.../application/server/{GetServerQuery,DeleteRoleUseCase}.java`, `.../application/member/LeaveServerUseCase.java`, `.../interfaces/rest/{ServerController,CommunityExceptionHandler}.java`, `.../infrastructure/messaging/OutboxDomainEventPublisher.java`
- Test: `.../interfaces/rest/{OverwriteIT,MyPermissionsIT}.java`, `.../infrastructure/persistence/channel/ChannelOverwriteRepositoryImplIT.java`

**Interfaces:**
- Produces: `ChannelOverwriteRepository`: `List<ChannelOverwrite> findByChannel(ChannelId)`, `Map<ChannelId, List<ChannelOverwrite>> findByServer(ServerId)`, `void upsert(ChannelId, ChannelOverwrite)`, `boolean remove(ChannelId, OverwriteTarget, UUID targetId)`, `void removeByTarget(ServerId, UUID targetId)` (xóa mọi overwrite của role/member đó trong server).
- Event `ChannelPermissionsChanged(ServerId, ChannelId)` → routing key `channel.permissions-changed`, payload `serverId, channelId`.
- REST: `PUT /api/v1/servers/{serverId}/channels/{channelId}/permissions/{targetType}/{targetId}` body `{allow:long, deny:long}` → `204`; `DELETE` cùng đường dẫn → `204` (không có → `404`). `targetType ∈ {role, member}` (không phân biệt hoa thường, lạ → `400`). Cần `MANAGE_ROLES`; `allow`/`deny` chỉ gồm bit `CHANNEL_SCOPE`, `allow ∩ deny = ∅`, `allow` phải `canGrant`; đích role → `canManageRole` (role `@everyone` cho phép khi actor có `MANAGE_ROLES`, vì overwrite không đổi quyền gốc của role); đích member → phải là member của server; mỗi lần ghi/xóa phát `ChannelPermissionsChanged`.
- `GET /api/v1/servers/{serverId}/permissions/@me` → `200 {server:long, channels:[{channelId, permissions:long}]}` (`PermissionCalculator.server` và `.channel` cho từng kênh). `GetServerQuery` chỉ trả kênh có `VIEW_CHANNEL` (category giữ nguyên, ponytail: ẩn category rỗng là backlog). `DeleteRoleUseCase` gọi `removeByTarget(serverId, roleId)`; `LeaveServerUseCase` gọi `removeByTarget(serverId, userId)`.

- [ ] **Step 1: Viết test** — `OverwriteIT`: kịch bản `announcements` của `permission-model.md` §7: owner tạo kênh, đặt overwrite `@everyone` deny `SEND_MESSAGES`, role `Mod` allow `SEND_MESSAGES`; `@me` của member thường không có bit `SEND_MESSAGES` ở kênh đó còn có ở `general`, của Mod có; overwrite MEMBER deny thắng allow của role; `deny VIEW_CHANNEL` cho `@everyone` → kênh biến mất khỏi `GET /servers/{id}` của member thường nhưng admin/owner vẫn thấy; bit server-scope (`BAN_MEMBERS`) trong `allow` → 400; `allow & deny` chồng nhau → 400; thiếu `MANAGE_ROLES` → 403; xóa role → overwrite của role biến mất (đọc lại qua repository); rời server rồi join lại → overwrite MEMBER cũ không còn; outbox có `ChannelPermissionsChanged` sau mỗi PUT/DELETE. `MyPermissionsIT`: owner thấy `ALL`; người ngoài 404. `ChannelOverwriteRepositoryImplIT`: upsert ghi đè, `findByServer` gom theo kênh, `removeByTarget`.
- [ ] **Step 2: Chạy** → FAIL. **Step 3: Implement.** **Step 4: Chạy lại** → PASS (kèm mọi IT trước).
- [ ] **Step 5: Dừng để người dùng commit** — `feat(community): overwrite theo kênh, permissions/@me, lọc kênh theo VIEW_CHANNEL`.

### Task 7: server_bans, khóa advisory và chặn join khi bị ban

**Files:**
- Create: `resources/db/migration/V3__server_bans.sql`, `.../domain/ban/{ServerBan,ServerBanRepository,MemberBanned,MemberUnbanned}.java`, `.../application/port/MemberLock.java`, `.../application/invite/BannedFromServerException.java`, `.../infrastructure/persistence/ban/{BanDao,BanJpaEntity,BanKey,ServerBanRepositoryImpl}.java`, `.../infrastructure/persistence/member/PgAdvisoryMemberLock.java`
- Modify: `.../application/invite/AcceptInviteUseCase.java`, `.../interfaces/rest/CommunityExceptionHandler.java`, `.../infrastructure/messaging/OutboxDomainEventPublisher.java`, `CommunityApplicationIT.java` (bảng `server_bans` nay phải có)
- Test: `.../domain/ban/ServerBanTest.java`, `.../infrastructure/persistence/ban/ServerBanRepositoryImplIT.java`, `.../infrastructure/persistence/member/PgAdvisoryMemberLockIT.java`, `.../interfaces/rest/InviteIT.java`

**Interfaces:**
- `V3__server_bans.sql`: `server_bans(server_id uuid REFERENCES servers ON DELETE CASCADE, user_id uuid, banned_by uuid NOT NULL, reason varchar(512), created_at timestamptz NOT NULL, PRIMARY KEY (server_id, user_id))` theo `data-model.md`.
- `ServerBan` (final class): `static ServerBan create(ServerId, UserId target, UserId bannedBy, String reasonOrNull, boolean wasMember, Instant now)` (trim, rỗng → null, > 512 → `DomainValidationException`; phát `MemberBanned(ServerId, UserId, UserId bannedBy, String reason, boolean wasMember)`); `static ServerBan rehydrate(...)`; `void lift(UserId by)` phát `MemberUnbanned(ServerId, UserId, UserId unbannedBy)`; getter `serverId/userId/bannedBy/reason/createdAt`; `pullEvents()`. Outbox: `member.banned` / `member.unbanned`, `aggregateType "Ban"`, `aggregateId = serverId`.
- `ServerBanRepository`: `boolean addIfAbsent(ServerBan)` (`INSERT … ON CONFLICT DO NOTHING`), `Optional<ServerBan> find(ServerId, UserId)`, `boolean remove(ServerId, UserId)`, `List<ServerBan> page(ServerId, UserId afterUserId, int limit)` (keyset theo `user_id`).
- `MemberLock { void lock(ServerId, UserId) }` (application/port); `PgAdvisoryMemberLock` chạy `select pg_advisory_xact_lock(hashtextextended(:key, 0))` với `key = serverId + ':' + userId`; chỉ gọi từ use case `@Transactional` (khóa nhả khi commit/rollback).
- `AcceptInviteUseCase`: sau khi nạp invite và server, **gọi `memberLock.lock` rồi** `serverBanRepository.find` — có ban → ném `BannedFromServerException` → `403 BANNED_FROM_SERVER`; trước `addIfAbsent` và `tryConsume` (bị ban thì không tiêu `uses`).

- [ ] **Step 1: Viết test** — `ServerBanTest` (reason 513 ký tự bị từ chối, rỗng → null, event `wasMember`); `ServerBanRepositoryImplIT` (`addIfAbsent` lần hai trả `false`, `remove`, `page` keyset); `PgAdvisoryMemberLockIT`: hai thread cùng khóa `(server,user)`, thread hai chỉ qua sau khi thread một commit; khóa khác cặp không chặn nhau; `InviteIT`: chèn ban qua repository rồi accept → `403 BANNED_FROM_SERVER`, `uses` của invite không đổi.
- [ ] **Step 2: Chạy** → FAIL. **Step 3: Implement.** **Step 4: Chạy lại** → PASS.
- [ ] **Step 5: Dừng để người dùng commit** — `feat(community): server_bans, khóa advisory theo cặp, chặn join khi bị ban`.

### Task 8: Kick, ban, gỡ ban, danh sách ban (FR-036, FR-037, FR-039)

**Files:**
- Create: `.../domain/member/MemberKicked.java`, `.../application/member/{KickMemberUseCase,MemberNotFoundException}.java`, `.../application/ban/{BanMemberUseCase,UnbanMemberUseCase,ListBansQuery,BanView,BanPage}.java`, `.../interfaces/rest/{BanController,BanRequest,BanResponse,BanPageResponse}.java`
- Modify: `.../domain/member/Member.java` (`void kick(UserId by)` phát `MemberKicked(ServerId, UserId, UserId kickedBy)`), `.../interfaces/rest/{MemberController,CommunityExceptionHandler}.java`, `.../infrastructure/messaging/OutboxDomainEventPublisher.java` (`member.kicked`)
- Test: `.../interfaces/rest/{KickIT,BanIT}.java`

**Interfaces:**
- Consumes: `ServerAccess.requirePermission`, `RoleHierarchyPolicy.canModerate`, `MemberLock`, `ServerBanRepository`, `ChannelOverwriteRepository.removeByTarget`.
- REST: `DELETE /api/v1/servers/{serverId}/members/{userId}` → `204`; `PUT /api/v1/servers/{serverId}/bans/{userId}` body tùy chọn `{reason?}` → `204`; `DELETE …/bans/{userId}` → `204`; `GET …/bans?cursor&limit` (limit 1..100, mặc định 50, cursor base64url của `userId` như members) → `200 {items:[{userId, bannedBy, reason, createdAt}], nextCursor}`.
- Thứ tự kiểm tra (theo `activity-kick-ban-check.md`): người ngoài → 404; thiếu `KICK_MEMBERS`/`BAN_MEMBERS` → 403; **kick** target không là member → 404; target là owner hoặc chính actor → 403; không phải owner-actor và `canModerate` sai → 403. Trong cùng transaction, sau `memberLock.lock(serverId, target)`: kick xóa member (+ `member_roles` cascade) và `removeByTarget`, phát `MemberKicked`; ban `addIfAbsent(ServerBan)` (đã ban → không làm gì, **không** phát event, vẫn `204`), xóa member nếu có, `removeByTarget`, phát `MemberBanned{wasMember}`; gỡ ban `remove` (không có → 404) rồi phát `MemberUnbanned`. Người bị kick join lại được bằng invite; người bị gỡ ban cũng vậy.
- `{userId}` trong `DELETE /members/{userId}` là UUID; `DELETE /members/@me` vẫn là rời server.

- [ ] **Step 1: Viết test** — `KickIT`: Mod (`KICK_MEMBERS`, position 1) kick member thường → 204, `GET /members` không còn, outbox có `MemberKicked`; kick lại → 404; kick Admin (position 3) / ngang cấp / owner / chính mình → 403; member thường → 403; người ngoài → 404; sau kick, invite cũ join lại → 200 và `roleIds` rỗng; `DELETE /members/@me` vẫn rời server. `BanIT`: ban member → 204, accept → `403 BANNED_FROM_SERVER`; ban lần hai → 204 và đúng 1 dòng `server_bans`, 1 event; ban người chưa là member (`wasMember=false`) → 204; `reason` 513 ký tự → 400; ban owner/chính mình/người cao hơn → 403; `GET /bans` có `bannedBy`, `reason`, `createdAt`, phân trang cursor; gỡ ban → 204, `GET /bans` rỗng, accept → 200; gỡ ban người không bị ban → 404; mọi endpoint thiếu quyền → 403, người ngoài → 404.
- [ ] **Step 2: Chạy** → FAIL. **Step 3: Implement.** **Step 4: Chạy lại** → PASS (kèm mọi IT trước).
- [ ] **Step 5: Dừng để người dùng commit** — `feat(community): kick, ban, gỡ ban và danh sách ban FR-036 FR-037 FR-039`.

### Task 9: IT đồng thời ban×join, demo P3, tài liệu, đóng phase

**Files:**
- Create: `.../interfaces/rest/BanJoinConcurrencyIT.java`, `infra/scripts/demo-p3.sh`
- Modify: `README.md`, `docs/02-architecture/services.md`, `docs/05-plan/backend-plan.md`, `docs/journal.md`, `docs/02-architecture/api-conventions.md` (nếu thiếu mã lỗi), `infra/scripts/demo-p2.sh` không đổi

**Interfaces:**
- `BanJoinConcurrencyIT`: invite `maxUses=0`; lặp 50 vòng, mỗi vòng một user mới `U`; hai thread bắt đầu cùng lúc bằng `CountDownLatch`: ban `U` (qua REST) và accept invite bằng `U`; sau khi cả hai xong, đọc DB: **không** tồn tại cặp `(members.user_id = U) ∧ (server_bans.user_id = U)`, và dòng ban luôn tồn tại; accept trả `200` hoặc `403 BANNED_FROM_SERVER`, không bao giờ `500`.
- `demo-p3.sh` (dùng qua gateway `:8080`, `POSTGRES_PORT=5433`, theo khuôn `demo-p2.sh`, tốn ít request auth để không đụng rate limit 10/phút): A tạo server + kênh `announcements`; A tạo role `Mod` (`SEND_MESSAGES|KICK_MEMBERS|BAN_MEMBERS`); đặt overwrite `@everyone` deny `SEND_MESSAGES` ở `announcements`; B, C join; A gán `Mod` cho B; in `permissions/@me` của B (có `SEND_MESSAGES`) và C (không có) ở `announcements`; B ban C với lý do; C accept lại → `403 BANNED_FROM_SERVER`; B gỡ ban; C join được. Mỗi bước `assert` mã HTTP, exit ≠ 0 nếu sai.
- Docs: README bảng endpoint P3 và cách chạy `demo-p3.sh`; `services.md` ghi các lựa chọn P3 (role mới mặc định ở vị trí 1, đánh số dày, `canPlaceAt` hai chế độ, overwrite không áp cho bit server-scope, ẩn kênh nhưng chưa ẩn category); `backend-plan.md` đánh `[x]` các mục P3 (ghi "gRPC `PermissionService` dùng lại calculator ở P4"); `journal.md` thêm 3-5 dòng đã học (domain service thuần + test dạng bảng, hiệu đối xứng khi gán role, deferrable unique để đổi chỗ vị trí, advisory lock chống race ban×join và vì sao `SELECT` rồi `INSERT` không đủ, ban là audit tối thiểu bằng chính dữ liệu nghiệp vụ).

- [ ] **Step 1: Viết test** `BanJoinConcurrencyIT`.
- [ ] **Step 2: Chạy** `-Dit.test=BanJoinConcurrencyIT` → PASS. Kiểm đột biến: tạm bỏ `memberLock.lock` ở `AcceptInviteUseCase` → IT phải **FAIL** (ít nhất một vòng ra member bị ban); khôi phục bằng đúng file gốc rồi chạy lại → PASS.
- [ ] **Step 3: Viết `demo-p3.sh` và tài liệu**; chạy toàn repo `./mvnw -B -o verify` → exit 0. Chạy demo bằng compose **chỉ khi** người dùng cho phép (`POSTGRES_PORT=5433`, không chạm Postgres 5432 của máy dev).
- [ ] **Step 4: Dừng để người dùng commit và tag** — `feat(community): IT ban×join, demo P3, đóng phase` (tag `v0.3.0` do người dùng quyết).

---

## Self-Review

- **Phủ spec:** FR-032 (T3), FR-033 (T4), FR-034 (T6), FR-036 (T8), FR-037 (T7+T8), FR-039 (T8); backend-plan P3: #1 → T1, #2 → T3, #3 → T4+T6, #4 → T5, #5 → T6, #6 → `RoleIT` 409 (T3) và PATCH kênh song song (T5), #7 → T7, #8 → T8, #9 → T8, #10 → T8, #11 → T7+T9. FR-035, FR-038, gRPC, cache quyền: ngoài phạm vi, cố ý không làm.
- **Nhất quán kiểu:** `Actor`, `Authorized`, `ChannelOverwrite`, `OverwriteTarget`, `MemberLock`, `ServerBanRepository` xuất hiện ở Interfaces trước khi dùng; `Member.rehydrate` đổi chữ ký ở T2 và mọi chỗ gọi (MemberRepositoryImpl, test) cập nhật trong T2.
- **Quyết định tôi đã chọn (người dùng có thể đổi):** (1) role mới mặc định ở vị trí 1 và đánh số dày thay vì nhập số tùy ý; (2) `canPlaceAt` hai chế độ (chèn mới `≤ highest`, di chuyển `< highest`); (3) gán role chỉ kiểm theo role thêm/bớt, không kiểm thứ bậc với target (đúng WF-08); (4) `reason` rỗng → null; ban người chưa là member được phép; ban lại → `204` im lặng; (5) overwrite không áp cho bit server-scope; `allow` phải `canGrant`; (6) `GET /servers/{id}` ẩn kênh không có `VIEW_CHANNEL` nhưng chưa ẩn category; (7) `ChannelPermissionsChanged` phát ở mỗi PUT/DELETE kể cả khi giá trị không đổi; (8) role bị xóa không phát `MemberRolesChanged` cho từng người giữ (cache P4 vô hiệu hóa bằng `RoleDeleted`); (9) tên role `1..100` sau trim, `color` `0..0xFFFFFF`.

## Execution Handoff

Hai cách chạy: **Subagent-driven** (mỗi task một implementer + reviewer, review toàn nhánh bằng model mạnh ở cuối; như P2) hoặc **Native** (tôi làm toàn bộ trong phiên này, một lượt review cuối). Khuyến nghị subagent-driven vì T2, T6, T7, T8 đụng bảo mật và đồng thời.
