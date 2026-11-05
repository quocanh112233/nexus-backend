# P0 Nền móng + P1 Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dựng hạ tầng local (Postgres/Redis/RabbitMQ, CI) và `identity-service` chạy trọn vòng: đăng ký, đăng nhập, refresh rotation, profile, avatar Cloudinary, image Docker trên GHCR.

**Architecture:** Maven multi-module trong repo `nexus-backend`. `identity-service` theo DDD 4 lớp (domain/application/infrastructure/interfaces) với lớp DAO (ADR-009): `XxxRepository` (domain) → `XxxRepositoryImpl` → `XxxDao` + `XxxMapper` + `XxxJpaEntity`. Event ghi vào bảng `outbox_event` cùng transaction (chưa relay, relay làm ở P5). Một instance Postgres, schema `identity` thuộc user `identity_svc`.

**Tech Stack:** Java 21, Spring Boot 4.1.x, Maven wrapper, Spring Security (resource server, RS256), Spring Data JPA, Flyway, PostgreSQL 16, Redis 7, RabbitMQ 4, Testcontainers, ArchUnit, AssertJ, springdoc, Docker, GitHub Actions, Trivy.

**Spec:** [roadmap.md](../../05-plan/roadmap.md) (P0, P1), [backend-plan.md](../../05-plan/backend-plan.md#p0--nền-móng-s), [backend-structure.md](../../04-project-structure/backend-structure.md), [infra-structure.md](../../04-project-structure/infra-structure.md), [data-model.md](../../02-architecture/data-model.md#1-schema-identity-user-identity_svc), [security.md](../../02-architecture/security.md), [api-conventions.md](../../02-architecture/api-conventions.md), [services.md](../../02-architecture/services.md#1-identity-service), [devops-plan.md](../../05-plan/devops-plan.md), [coding-conventions.md](../../04-project-structure/coding-conventions.md).

**Phạm vi:** chỉ backend. Trang web tối giản của P1 (đăng ký/đăng nhập/profile) nằm trong repo `nexus-frontend` và có plan riêng theo [frontend-plan.md](../../05-plan/frontend-plan.md); demo của plan này dùng Swagger UI và `curl`.

## Global Constraints

- Java 21 (`maven.compiler.release=21`; máy dev đang có JDK 25 nên chỉ biên dịch với `--release 21`, image Docker dùng Temurin 21). Spring Boot 4.1.x (quyết định người dùng 2026-10-24, thay cho 3.4 đã hết hỗ trợ). Maven multi-module (P-03). Không Lombok (ADR-008).
- Value object, REST DTO, event payload: `record`. Aggregate/entity domain: `final class`, constructor + getter viết tay, không setter công khai, đổi trạng thái qua method nghiệp vụ. `XxxJpaEntity` không ra khỏi `infrastructure`.
- `domain` chỉ phụ thuộc JDK; `application` chỉ phụ thuộc `domain` (cho phép `@Transactional`); `interfaces` không phụ thuộc `infrastructure`. ArchUnit cưỡng chế.
- `@Transactional` chỉ đặt trên method của use case. Constructor injection, không `@Autowired` field. Không gọi DAO từ `application`.
- Comment: chỉ **1 dòng** trước class/interface/hàm ghi chức năng; **test đủ 3 dòng** (kịch bản / input / output); không comment mồ côi; sửa code có comment thì sửa comment. Không Javadoc nhiều dòng.
- Test: AssertJ; class `XxxTest` (unit), `XxxIT` (integration, Failsafe); mock chỉ port của `application/port`; Testcontainers Postgres thật cho DAO/IT.
- Id: UUIDv7 sinh ở ứng dụng (`UuidCreator.getTimeOrderedEpoch()` từ `com.github.f4b6a3:uuid-creator`; lựa chọn của tôi vì JDK 21 không có UUIDv7). Thời gian `timestamptz`/`Instant` UTC; inject `java.time.Clock`.
- DB: schema `identity`, user `identity_svc`; Flyway `V<n>__<mo_ta_snake>.sql`, không sửa migration đã chạy; `ddl-auto=validate`; unique `lower(email)`, `lower(username)`.
- JWT: RS256, `kid` trong header; claim `sub, username, iat, exp, iss=nexus-identity, aud=nexus, jti`; access token **15 phút**. Refresh token: 256-bit ngẫu nhiên, DB lưu **SHA-256**, **30 ngày**, cookie `nexus_rt` `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`. BCrypt cost **12**.
- Lỗi: RFC 9457 Problem Details + `code` ổn định: `VALIDATION_FAILED` 400, `UNAUTHENTICATED` 401, `REFRESH_TOKEN_REUSED` 401, `PERMISSION_DENIED` 403, `NOT_FOUND` 404, `EMAIL_TAKEN`/`USERNAME_TAKEN` 409, `VERSION_CONFLICT` 409. Đăng nhập sai luôn trả 401 `UNAUTHENTICATED` chung chung.
- Cổng: identity REST `8081`, actuator `8181` (không publish ra host). Tên image `ghcr.io/quocanh112233/nexus-identity-service`.
- Container: multi-stage, `eclipse-temurin:21-jre-alpine`, chạy uid `10001`, `HEALTHCHECK`.
- Commit: Conventional Commits (`feat(identity): ...`), kết thúc bằng dòng `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Làm trên nhánh `feat/p0-nen-mong` rồi `feat/p1-identity`; **không push, không tạo PR** trừ khi người dùng yêu cầu.
- Không commit `.env`, khóa PEM, `CLOUDINARY_API_SECRET` thật.

## Review Focus

- Đăng ký `A@X.com` khi `a@x.com` đã tồn tại (và username khác hoa/thường) → `409` đúng `code`, không `500` (Task 9).
- Đăng nhập email không tồn tại vs sai mật khẩu → response giống hệt nhau (status + body) và vẫn chạy một lần BCrypt giả để thời gian gần nhau (Task 10).
- Gọi `/auth/refresh` không cookie, cookie rác, cookie hết hạn → `401 UNAUTHENTICATED`, không `500` (Task 12).
- `GET /users?ids=` với > 100 id, id không phải UUID, hoặc rỗng → `400` / `200 []`, không `500` (Task 13).
- JWT giả: `alg=none`, ký bằng khóa khác, hết hạn, sai `aud` → `401` (Task 10).

---

# Phần P0 — Nền móng

### Task 1: Khung Maven + Spotless + file gốc repo

**Files:**
- Create: `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitignore`, `.dockerignore`, `README.md` (thay nội dung hiện có `# nexus-backend`), `libs/nexus-common-web/pom.xml`, `libs/nexus-test-support/pom.xml`, `libs/nexus-common-messaging/pom.xml`, `libs/pom.xml`, `services/pom.xml`
- Test: lệnh `./mvnw verify`, `./mvnw spotless:check`

**Interfaces:**
- Produces: parent `com.nexus:nexus-backend:0.1.0-SNAPSHOT` (packaging `pom`) với `dependencyManagement` import BOM Spring Boot 4.1.x; modules `libs/*`, `services/*`; property `java.version=21`; plugin Spotless (google-java-format) gắn vào phase `verify`; Surefire (`*Test`) + Failsafe (`*IT`).
- Lệch có chủ đích so với backend-plan: chỉ tạo các module `libs` P1 cần (`nexus-common-web`, `nexus-common-messaging`, `nexus-test-support`); `proto`, `nexus-common-grpc` tạo ở P4 (YAGNI).

- [ ] **Step 1: Tạo nhánh và commit docs**
  Chạy `git checkout -b feat/p0-nen-mong`; `git add docs && git commit` với message `docs: thêm bộ tài liệu thiết kế Nexus`.
- [ ] **Step 2: Kiểm tra công cụ** — `java -version` (chấp nhận JDK ≥ 21), `docker compose version`. Nếu JDK 21 không có thì vẫn tiếp tục (release 21 trên JDK 25); ghi vào README mục "Yêu cầu".
- [ ] **Step 3: Sinh Maven wrapper** bằng `mvn -N wrapper:wrapper -Maven=3.9.x` (bản 3.9 mới nhất), tạo parent `pom.xml` và 3 module rỗng (chỉ `pom.xml`, packaging `jar`), `.gitignore` (target, `.env`, `infra/secrets/`, `compose.override.yaml`, IDE), `.dockerignore` (`**/target`, `.git`, `docs`, `infra/secrets`).
- [ ] **Step 4: Chạy** `./mvnw -q verify` → Expected: `BUILD SUCCESS`.
- [ ] **Step 5: Cố ý làm sai format** một file Java tạm trong `libs/nexus-common-web/src/main/java/.../Tmp.java`, chạy `./mvnw spotless:check` → Expected: FAIL; chạy `./mvnw spotless:apply` rồi `spotless:check` → PASS; xóa file tạm.
- [ ] **Step 6: Viết `README.md`** gồm: mô tả, yêu cầu (JDK ≥ 21, Docker), các lệnh trong [backend-structure.md](../../04-project-structure/backend-structure.md#1-build-tool-maven-multi-module-p-03-adr-006) (chỉ những lệnh đã có thật).
- [ ] **Step 7: Commit** `build: khung Maven multi-module, Spotless, wrapper`.

### Task 2: Hạ tầng Docker Compose + cách ly schema

**Files:**
- Create: `compose.yaml`, `infra/compose.infra.yaml`, `infra/postgres/init/01-create-schemas-and-users.sh`, `.env.example`, `infra/scripts/gen-jwt-keys.sh`, `infra/scripts/verify-isolation.sh`
- Test: `infra/scripts/verify-isolation.sh` (script tự kiểm: exit 0 khi cách ly đúng)

**Interfaces:**
- Produces: service compose `postgres` (db `nexus`, 4 schema + 4 user `identity_svc`, `community_svc`, `messaging_svc`, `notification_svc`), `redis`, `rabbitmq` (vhost `nexus`); biến `.env`: `POSTGRES_ADMIN_PASSWORD`, `IDENTITY_DB_PASSWORD`, `COMMUNITY_DB_PASSWORD`, `MESSAGING_DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD`, `RABBITMQ_ADMIN_USER`, `RABBITMQ_ADMIN_PASSWORD`; file khóa `infra/secrets/jwt-private.pem` (PKCS#8 RSA 2048) dùng ở Task 15.
- Lệch có chủ đích: RabbitMQ P0 chỉ dùng user admin từ env + vhost `nexus` (`RABBITMQ_DEFAULT_*`); `definitions.json` + user theo service + `rabbitmq_prometheus` hoãn tới P5/P9 (chưa có consumer).

- [ ] **Step 1: Viết script kiểm cách ly trước** `verify-isolation.sh`: nạp `.env`; chạy `docker compose exec -T postgres env PGPASSWORD=$MESSAGING_DB_PASSWORD psql -U messaging_svc -d nexus -c "SELECT 1 FROM community.members"`; script **exit 0 chỉ khi** output chứa `permission denied for schema community`; ngược lại in lỗi và exit 1. Thêm kiểm thứ hai: `identity_svc` tạo được `CREATE TABLE identity.tmp_check(id int)` rồi `DROP`.
- [ ] **Step 2: Chạy script khi chưa có compose** → Expected: FAIL (không có container).
- [ ] **Step 3: Viết `infra/compose.infra.yaml`** theo mẫu [infra-structure.md §2](../../04-project-structure/infra-structure.md#2-mẫu-dịch-vụ-rút-gọn) cho `postgres` (postgres:16-alpine, mount thư mục init, volume `pgdata`, healthcheck `pg_isready`), `redis` (redis:7-alpine, healthcheck `redis-cli ping`), `rabbitmq` (rabbitmq:4-management-alpine, `RABBITMQ_DEFAULT_USER/PASS/VHOST=nexus`, cổng `15672`, healthcheck `rabbitmq-diagnostics -q ping`). `compose.yaml`: `name: nexus`, `include: [infra/compose.infra.yaml]`. Chép script init từ [infra-structure.md §3](../../04-project-structure/infra-structure.md#3-init-script-postgres).
- [ ] **Step 4: Viết `.env.example`** (giá trị giả, `change-me`) và `gen-jwt-keys.sh` (`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048` → `infra/secrets/jwt-private.pem`, `chmod 644` vì container non-root uid 10001 phải đọc được bind mount; chỉ dùng cho local, ghi chú vào README).
- [ ] **Step 5: Chạy** `cp .env.example .env && docker compose up -d --wait` → Expected: 3 container `healthy`.
- [ ] **Step 6: Chạy** `infra/scripts/verify-isolation.sh` → Expected: exit 0. Mở `http://localhost:15672` đăng nhập được bằng user admin.
- [ ] **Step 7: Chạy** `docker compose down -v && docker compose up -d --wait && infra/scripts/verify-isolation.sh` → Expected: vẫn exit 0 (init chạy lại từ volume trống).
- [ ] **Step 8: Commit** `chore(infra): compose Postgres/Redis/RabbitMQ, init schema theo service`.

### Task 3: Khung CI

**Files:**
- Create: `.github/workflows/repo-checks.yml`, `.github/workflows/_backend-service.yml`, `.github/workflows/proto.yml`, `.github/pull_request_template.md`

**Interfaces:**
- Produces: reusable workflow `_backend-service.yml` với inputs `module` (string, vd `services/identity-service`) và `image` (string), theo bảng ở [devops-plan.md §3.2](../../05-plan/devops-plan.md#32-reusable-workflow-_backend-serviceyml); `repo-checks.yml` chạy trên mọi PR và push `main`: `./mvnw -B spotless:check` và `docker compose config -q` (với `.env.example` copy thành `.env`).
- `proto.yml`: workflow khung chỉ kích hoạt khi `proto/**` đổi, chưa có job `buf` (thêm ở P4) — job duy nhất `echo` để không đỏ.

- [ ] **Step 1: Viết `repo-checks.yml`** (checkout, setup-java 21 temurin cache maven, hai lệnh trên).
- [ ] **Step 2: Viết `_backend-service.yml`** đúng các bước bảng devops-plan §3.2 (Maven verify `-pl ${{ inputs.module }} -am`, upload báo cáo, docker meta tag `sha-<7>`/`latest`/`{{version}}`, buildx, login GHCR khi không phải PR từ fork, build-push với cache `type=gha,scope=<image>`, Trivy `CRITICAL,HIGH` `ignore-unfixed` fail chỉ CRITICAL, upload SARIF). Pin version action theo tên trong bảng.
- [ ] **Step 3: Kiểm tra cú pháp** bằng `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:latest` → Expected: không lỗi.
- [ ] **Step 4: Commit** `ci: workflow kiểm tra repo, reusable workflow build service`. (Việc xác nhận "CI xanh trên PR rỗng" chỉ làm được khi người dùng push; ghi vào báo cáo cuối phase.)

---

# Phần P1 — Identity trọn vòng

### Task 4: `nexus-common-web` — Problem Details và request id

**Files:**
- Create: `libs/nexus-common-web/src/main/java/com/nexus/common/web/{ApiException,GlobalExceptionHandler,RequestIdFilter,ProblemBodies}.java`
- Test: `libs/nexus-common-web/src/test/java/com/nexus/common/web/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces:
  - `public class ApiException extends RuntimeException { ApiException(HttpStatus status, String code, String detail) }` với getter `status()`, `code()`.
  - `@RestControllerAdvice GlobalExceptionHandler`: `ApiException` → Problem Details `{type: "https://nexus.dev/errors/<code-kebab>", title, status, code, detail, instance, requestId}`; `MethodArgumentNotValidException`/`ConstraintViolationException` → 400 `VALIDATION_FAILED` kèm `errors[{field,message}]`; `HttpMessageNotReadableException` → 400 `VALIDATION_FAILED`; mọi `Exception` khác → 500 `INTERNAL_ERROR` với `detail` cố định (không lộ stack/message).
  - `RequestIdFilter` (`OncePerRequestFilter`, order cao nhất): đọc `X-Request-Id` hoặc sinh UUID, đặt vào MDC key `requestId` và header response.
  - `ProblemBodies.write(HttpServletResponse, HttpStatus, String code, String detail)` dùng cho `AuthenticationEntryPoint`/`AccessDeniedHandler`.

- [ ] **Step 1: Viết test (standalone MockMvc với controller giả trong thư mục test)**:
  - `apiExceptionBecomesProblemDetails` → status 409, JSON có `code=="EMAIL_TAKEN"`, `type` kết thúc `/email-taken`, `requestId` khớp header `X-Request-Id` gửi lên.
  - `invalidBodyListsFieldErrors` → 400, `code=="VALIDATION_FAILED"`, `errors[0].field=="email"`.
  - `unexpectedExceptionDoesNotLeakMessage` → 500, `code=="INTERNAL_ERROR"`, body không chứa chuỗi message gốc.
  - `requestIdGeneratedWhenMissing` → response có header `X-Request-Id` dạng UUID.
- [ ] **Step 2: Chạy** `./mvnw -pl libs/nexus-common-web -am test` → Expected: FAIL (class chưa có).
- [ ] **Step 3: Thêm dependency** `spring-boot-starter-web` (và validation) vào pom module theo artifactId mà Boot 4.1 dùng (tra start.spring.io hoặc BOM), rồi implement các class theo Interfaces. `GlobalExceptionHandler` đăng ký qua `@AutoConfiguration` + file `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` để service chỉ cần thêm dependency.
- [ ] **Step 4: Chạy lại** → Expected: PASS.
- [ ] **Step 5: Commit** `feat(libs): nexus-common-web Problem Details và RequestIdFilter`.

### Task 5: `nexus-test-support` + `nexus-common-messaging` (outbox)

**Files:**
- Create: `libs/nexus-test-support/src/main/java/com/nexus/testsupport/PostgresIntegrationTest.java`, `libs/nexus-common-messaging/src/main/java/com/nexus/common/messaging/{OutboxMessage,OutboxWriter,OutboxEventJpaEntity,OutboxEventDao,OutboxAutoConfiguration}.java`, `libs/nexus-common-messaging/src/main/resources/db/outbox/V0__outbox_template.sql` (không chạy tự động, chỉ làm mẫu SQL cho migration của từng service)
- Test: `libs/nexus-common-messaging/src/test/java/com/nexus/common/messaging/OutboxWriterIT.java`

**Interfaces:**
- Produces:
  - `PostgresIntegrationTest` (abstract class, chỉ dùng trong test): container singleton `postgres:16-alpine` dùng chung cho cả JVM, cung cấp datasource cho Spring (`@ServiceConnection` hoặc `@DynamicPropertySource`).
  - `public record OutboxMessage(String aggregateType, UUID aggregateId, String eventType, int eventVersion, String routingKey, Map<String,Object> payload)`.
  - `public class OutboxWriter { OutboxWriter(OutboxEventDao dao, Clock clock); void append(OutboxMessage m) }` — ghi 1 dòng `outbox_event` (`id` UUIDv7, `headers='{}'`, `created_at=now`, `published_at=null`, `attempts=0`); không tự mở transaction (chạy trong transaction của use case).
  - `OutboxEventJpaEntity` ánh xạ bảng `outbox_event` (cột theo [data-model.md §0](../../02-architecture/data-model.md#0-quy-ước-chung)); `OutboxEventDao extends JpaRepository<OutboxEventJpaEntity, UUID>`.
  - Bảng `outbox_event` do **migration của từng service** tạo (module lib không chạy Flyway).

- [ ] **Step 1: Viết `OutboxWriterIT`** (trong module `nexus-common-messaging`, dùng một `@SpringBootTest` cấu hình nhỏ + bảng tạo bằng SQL mẫu): `appendStoresRowWithPayload` → sau `append` có đúng 1 dòng, `routing_key=="user.registered"`, `payload->>'userId'` khớp, `published_at IS NULL`.
- [ ] **Step 2: Chạy** `./mvnw -pl libs/nexus-common-messaging -am verify` → Expected: FAIL.
- [ ] **Step 3: Implement** các class trên; thêm dependency Testcontainers theo BOM Boot 4.1 (Testcontainers 2.x dùng artifactId có tiền tố `testcontainers-`; xác nhận trong BOM trước khi ghi pom). JSON payload ánh xạ bằng `@JdbcTypeCode(SqlTypes.JSON)`.
- [ ] **Step 4: Chạy lại** → Expected: PASS.
- [ ] **Step 5: Commit** `feat(libs): outbox writer và test-support Testcontainers`.

### Task 6: Khung `identity-service` chạy được

**Files:**
- Create: `services/identity-service/pom.xml`, `.../com/nexus/identity/IdentityApplication.java`, `.../src/main/resources/{application.yml,application-local.yml,db/migration/V1__init.sql}`, `.../src/test/java/com/nexus/identity/{ArchitectureTest,IdentityApplicationIT}.java`
- Modify: `services/pom.xml` (thêm module)

**Interfaces:**
- Produces: ứng dụng Spring Boot chạy cổng 8081, actuator cổng 8181 (`management.server.port=8181`, bật `health/liveness`, `readiness`), springdoc tại `/swagger-ui.html` (bản springdoc-openapi tương thích Boot 4, xác nhận trong README của springdoc), log JSON bằng structured logging có sẵn của Boot (`logging.structured.format.console=ecs`) kèm MDC `requestId`; cấu hình `spring.flyway.schemas=identity`, `spring.flyway.default-schema=identity`, `spring.jpa.properties.hibernate.default_schema=identity`, `spring.jpa.hibernate.ddl-auto=validate`; biến môi trường `DB_URL`, `DB_USER`, `DB_PASSWORD`.
- `V1__init.sql` tạo `users`, `refresh_tokens`, `outbox_event` đúng [data-model.md §1](../../02-architecture/data-model.md#1-schema-identity-user-identity_svc) và bảng hạ tầng ([§0](../../02-architecture/data-model.md#0-quy-ước-chung)): index unique `ux_users_email ON users (lower(email))`, `ux_users_username ON users (lower(username))`, `ux_refresh_tokens_hash ON refresh_tokens (token_hash)`, index `(user_id)`, `(family_id)`, partial index outbox `WHERE published_at IS NULL`. `refresh_tokens.user_id` FK → `users(id)`.

- [ ] **Step 1: Viết test** `ArchitectureTest` (ArchUnit, `allowEmptyShould(true)`): `domainDoesNotDependOnFrameworks` (domain không import `org.springframework..`, `jakarta.persistence..`), `applicationDoesNotDependOnInfrastructureOrInterfaces`, `interfacesDoNotDependOnInfrastructure`, `jpaEntitiesStayInInfrastructure`. `IdentityApplicationIT extends PostgresIntegrationTest`: `contextLoadsAndFlywayCreatesIdentitySchema` → bảng `flyway_schema_history` nằm trong schema `identity`, tồn tại `identity.users`; `readinessProbeIsUp` → GET `/actuator/health/readiness` trên cổng quản trị trả `UP`.
- [ ] **Step 2: Chạy** `./mvnw -pl services/identity-service -am verify` → Expected: FAIL.
- [ ] **Step 3: Tạo module**: dependency `nexus-common-web`, `nexus-common-messaging`, `nexus-test-support` (test), starters web/validation/security/oauth2-resource-server/data-jpa/flyway (+ module Flyway cho PostgreSQL nếu Boot 4.1 tách riêng)/actuator, driver PostgreSQL, `uuid-creator`, springdoc, ArchUnit (test). Tạo thư mục package rỗng theo [backend-structure.md §3](../../04-project-structure/backend-structure.md#3-cấu-trúc-package-mỗi-service-ví-dụ-messaging-service) (`domain`, `application`, `infrastructure`, `interfaces` — mỗi cái cần ít nhất một class thật ở các task sau, **không** commit file `.gitkeep`).
- [ ] **Step 4: Chạy lại** → Expected: PASS.
- [ ] **Step 5: Commit** `feat(identity): khung service, Flyway V1, ArchUnit, actuator`.

### Task 7: Domain `User` và value object

**Files:**
- Create: `.../identity/domain/shared/{DomainEvent,DomainValidationException}.java`, `.../domain/user/{UserId,Email,Username,DisplayName,AboutMe,RawPassword,PasswordHash,MediaRef,User,UserRegistered,UserProfileUpdated,UserRepository,EmailAlreadyTakenException,UsernameAlreadyTakenException}.java`
- Test: `.../domain/user/{EmailTest,UsernameTest,DisplayNameTest,RawPasswordTest,UserTest}.java`

**Interfaces:**
- Produces (tất cả thuần Java):
  - `interface DomainEvent {}`; `class DomainValidationException extends RuntimeException`.
  - `record UserId(UUID value)` + `static UserId newId()`.
  - `record Email(String value)`: trim + lowercase, ≤ 254 ký tự, khớp `^[^@\s]+@[^@\s]+\.[^@\s]+$`. `record Username(String value)`: lowercase, khớp `^[a-z0-9_.]{2,32}$` (quy tắc do tôi chọn vì tài liệu chưa ghi). `record DisplayName(String value)`: trim, 1–32 ký tự. `record AboutMe(String value)`: trim, ≤ 190, `null` → `""`. `record RawPassword(String value)`: ≥ 8 ký tự và ≤ 72 byte UTF-8 (giới hạn BCrypt, tránh bị cắt lặng lẽ); `toString()` trả `"****"`. `record PasswordHash(String value)`: không rỗng. `record MediaRef(String publicId, String url)`: cả hai không rỗng.
  - `final class User`: `static User register(UserId id, Email email, Username username, DisplayName displayName, PasswordHash hash, Instant now)` (phát `UserRegistered`); `static User rehydrate(UserId id, Email email, Username username, DisplayName displayName, AboutMe aboutMe, PasswordHash hash, MediaRef avatar /*nullable*/, Instant createdAt, Instant updatedAt, Long version)`; `void updateProfile(DisplayName displayName, AboutMe aboutMe, Instant now)` (phát `UserProfileUpdated`); `void changeAvatar(MediaRef avatar, Instant now)` (phát `UserProfileUpdated`); `List<DomainEvent> pullEvents()` (trả rồi xóa); getter cho mọi field (`version()` là `null` khi chưa lưu).
  - `record UserRegistered(UserId userId, Username username, DisplayName displayName) implements DomainEvent`; `record UserProfileUpdated(UserId userId, DisplayName displayName, String avatarUrl /*nullable*/, AboutMe aboutMe) implements DomainEvent`.
  - `interface UserRepository { User save(User u); Optional<User> findById(UserId id); Optional<User> findByEmail(Email e); Optional<User> findByUsername(Username u); List<User> findAllByIds(Collection<UserId> ids); }` — `save` ném `EmailAlreadyTakenException`/`UsernameAlreadyTakenException` khi vi phạm unique.

- [ ] **Step 1: Viết test** (mỗi test đủ 3 dòng comment):
  - `EmailTest`: `normalizesCase` (`" A@X.Com "` → `a@x.com`); `rejectsMissingAt`; `rejectsOver254Chars`.
  - `UsernameTest`: `lowercases`; `rejectsSpaceAndUppercaseInvalidChars` (`"a b"`, `"a!"`); `rejectsLength1And33`.
  - `DisplayNameTest`: `rejectsBlank`; `rejects33Chars`.
  - `RawPasswordTest`: `rejects7Chars`; `rejects73Bytes` (kể cả chuỗi 40 ký tự có dấu = 80 byte); `toStringIsMasked`.
  - `UserTest`: `registerEmitsUserRegisteredOnce` (`pullEvents()` lần 1 có đúng 1 `UserRegistered`, lần 2 rỗng); `updateProfileEmitsEventWithNewValues`; `changeAvatarEmitsEventWithAvatarUrl`.
- [ ] **Step 2: Chạy** `./mvnw -pl services/identity-service test -Dtest='*Test'` → Expected: FAIL.
- [ ] **Step 3: Implement** theo Interfaces; kiểm tra bằng compact constructor của `record`, ném `DomainValidationException`.
- [ ] **Step 4: Chạy lại** (+ `ArchitectureTest`) → Expected: PASS.
- [ ] **Step 5: Commit** `feat(identity): domain User và value object`.

### Task 8: Lớp persistence cho `User` (DAO + Mapper + RepositoryImpl)

**Files:**
- Create: `.../infrastructure/persistence/user/{UserJpaEntity,UserDao,UserMapper,UserRepositoryImpl}.java`
- Test: `.../infrastructure/persistence/user/{UserMapperTest,UserRepositoryImplIT}.java`

**Interfaces:**
- Consumes: `UserRepository`, `User.rehydrate(...)` (Task 7).
- Produces:
  - `UserJpaEntity` (`@Entity @Table(name="users")`, `@Version Long version`, cột theo data-model: `id, email, username, password_hash, display_name, about_me, avatar_public_id, avatar_url, created_at, updated_at`).
  - `interface UserDao extends JpaRepository<UserJpaEntity, UUID>` + `Optional<UserJpaEntity> findByEmail(String)` / `findByUsername(String)` (so khớp chữ thường vì VO đã chuẩn hóa) + `List<UserJpaEntity> findAllByIdIn(Collection<UUID>)`.
  - `final class UserMapper { User toDomain(UserJpaEntity e); UserJpaEntity toJpa(User u); }`.
  - `UserRepositoryImpl implements UserRepository`: `save` dùng `saveAndFlush` để lỗi unique nổi lên trong lời gọi; bắt `DataIntegrityViolationException`, duyệt chuỗi cause lấy tên constraint (`ux_users_email` → `EmailAlreadyTakenException`, `ux_users_username` → `UsernameAlreadyTakenException`), constraint khác ném lại nguyên bản.

- [ ] **Step 1: Viết test**:
  - `UserMapperTest.roundTripKeepsAllFields` — `toDomain(toJpa(user))` bằng nhau từng field kể cả avatar `null` và non-null.
  - `UserRepositoryImplIT` (extends `PostgresIntegrationTest`): `savesAndFindsByEmailAndUsername`; `duplicateEmailDifferentCaseThrowsEmailTaken` (lưu user với `new Email("a@x.com")`, lưu user khác với `new Email("A@X.com")` → `EmailAlreadyTakenException`); `duplicateUsernameThrowsUsernameTaken`; `staleVersionSaveFailsWithOptimisticLock` (tải 2 bản, lưu bản 1, lưu bản 2 → `ObjectOptimisticLockingFailureException`); `findAllByIdsReturnsOnlyExisting`.
- [ ] **Step 2: Chạy** `./mvnw -pl services/identity-service -am verify` → Expected: FAIL.
- [ ] **Step 3: Implement** theo Interfaces. `toJpa` đặt `version` từ aggregate (`null` = bản ghi mới).
- [ ] **Step 4: Chạy lại** → Expected: PASS, `ArchitectureTest` vẫn xanh.
- [ ] **Step 5: Commit** `feat(identity): persistence cho User theo ADR-009`.

### Task 9: Đăng ký (FR-001)

**Files:**
- Create: `.../application/port/{PasswordHasher,DomainEventPublisher}.java`, `.../application/auth/{RegisterUserUseCase,RegisterCommand}.java`, `.../infrastructure/security/BcryptPasswordHasher.java`, `.../infrastructure/messaging/OutboxDomainEventPublisher.java`, `.../infrastructure/config/{ClockConfig,SecurityConfig}.java`, `.../interfaces/rest/{AuthController,RegisterRequest,MeResponse,IdentityExceptionHandler}.java`
- Test: `.../application/auth/RegisterUserUseCaseTest.java`, `.../interfaces/rest/RegisterIT.java`

**Interfaces:**
- Consumes: `UserRepository`, `User.register`, `OutboxWriter`, `ApiException`.
- Produces:
  - `interface PasswordHasher { PasswordHash hash(RawPassword raw); boolean matches(RawPassword raw, PasswordHash hash); }`; `BcryptPasswordHasher` cost 12.
  - `interface DomainEventPublisher { void publish(DomainEvent event); }`; `OutboxDomainEventPublisher` ánh xạ `UserRegistered` → `OutboxMessage("User", userId, "UserRegistered", 1, "user.registered", {userId, username, displayName})` và `UserProfileUpdated` → routing `user.profile-updated`, payload `{userId, displayName, avatarUrl, aboutMe}` (khóa theo [event-catalog.md](../../02-architecture/event-catalog.md)).
  - `RegisterCommand(String email, String username, String displayName /*nullable → dùng username*/, String password)`; `RegisterUserUseCase#execute(RegisterCommand): User` có `@Transactional`: tạo VO, hash, `User.register`, `userRepository.save`, publish mọi event từ `pullEvents()`.
  - REST: `POST /api/v1/auth/register` body `{email, username, displayName?, password}` → `201`, `Location: /api/v1/users/{id}`, body `MeResponse(id, email, username, displayName, aboutMe, avatarUrl, createdAt)`. Bean Validation ở `RegisterRequest` (record) cho độ dài/null; lỗi domain (`DomainValidationException`) → `400 VALIDATION_FAILED`; `EmailAlreadyTakenException` → `409 EMAIL_TAKEN`; `UsernameAlreadyTakenException` → `409 USERNAME_TAKEN` (map trong `IdentityExceptionHandler`).
  - `SecurityConfig` bước đầu: `permitAll` cho `/api/v1/auth/**`, `/.well-known/jwks.json`, `/v3/api-docs/**`, `/swagger-ui/**`; còn lại `authenticated` (tạm), CSRF tắt (API Bearer), session `STATELESS`.

- [ ] **Step 1: Viết test**:
  - `RegisterUserUseCaseTest` (mock `UserRepository`, `PasswordHasher`, `DomainEventPublisher`): `savesHashedPasswordNeverRaw`; `publishesUserRegistered`; `displayNameDefaultsToUsername`.
  - `RegisterIT` (`@SpringBootTest` + MockMvc + Postgres): `registerReturns201AndOutboxRow` (có 1 dòng `outbox_event` với `routing_key='user.registered'`); `duplicateEmailDifferentCaseReturns409EmailTaken`; `duplicateUsernameReturns409UsernameTaken`; `shortPasswordReturns400WithFieldError`; `concurrentSameUsernameOneCreatedOneConflict` (2 thread cùng username khác email → đúng một `201`, một `409`, bảng `users` có 1 dòng); `passwordIsNeverStoredInPlaintext` (cột `password_hash` bắt đầu `$2`).
- [ ] **Step 2: Chạy** → Expected: FAIL.
- [ ] **Step 3: Implement** theo Interfaces. Không kiểm tra trùng bằng `SELECT` trước (race); dựa vào unique index ([activity-registration.md](../../03-diagrams/activity-registration.md)).
- [ ] **Step 4: Chạy lại** → Expected: PASS.
- [ ] **Step 5: Commit** `feat(identity): đăng ký FR-001 kèm outbox UserRegistered`.

### Task 10: Ký JWT RS256, JWKS, đăng nhập (FR-002)

**Files:**
- Create: `.../application/port/TokenIssuer.java`, `.../application/auth/{LoginUseCase,LoginCommand,LoginResult,InvalidCredentialsException}.java`, `.../infrastructure/security/{JwtKeyProvider,JwtTokenIssuer,JwksController}.java`
- Modify: `.../infrastructure/config/SecurityConfig.java`, `.../interfaces/rest/AuthController.java`
- Test: `.../infrastructure/security/{JwtTokenIssuerTest,JwksControllerIT}.java`, `.../interfaces/rest/LoginIT.java`

**Interfaces:**
- Consumes: `PasswordHasher`, `UserRepository` (Task 7–9).
- Produces:
  - `record AccessToken(String value, Instant expiresAt)`; `interface TokenIssuer { AccessToken issue(User user); }`.
  - `JwtKeyProvider`: đọc PEM PKCS#8 từ `JWT_PRIVATE_KEY_PATH`, dẫn xuất public key từ private CRT key, `JWT_KEY_ID` làm `kid`; `RSAKey` Nimbus để ký và công bố.
  - `JwtTokenIssuer implements TokenIssuer` dùng `NimbusJwtEncoder`; claim đúng Global Constraints.
  - `GET /.well-known/jwks.json` trả public JWKS (`{"keys":[{kty,n,e,kid,alg:"RS256",use:"sig"}]}`, không có thành phần private).
  - `JwtDecoder` của chính Identity: `NimbusJwtDecoder.withPublicKey(...)` + validator `iss=nexus-identity`, `aud` chứa `nexus`, `exp`.
  - `LoginCommand(String email, String password, String userAgent, String ip)`; `LoginResult(AccessToken accessToken)` (Task 12 mở rộng thêm refresh token); `LoginUseCase#execute` ném `InvalidCredentialsException` khi sai; khi không tìm thấy user vẫn gọi `matches` với hash BCrypt giả dựng sẵn lúc khởi động.
  - REST: `POST /api/v1/auth/login` `{email, password}` → `200 {accessToken, tokenType:"Bearer", expiresIn:900}`; sai → `401 UNAUTHENTICATED` với `detail` chung `"Invalid credentials"`.

- [ ] **Step 1: Viết test**:
  - `JwtTokenIssuerTest` (khóa sinh runtime bằng `KeyPairGenerator`): `tokenVerifiesWithPublicKeyAndHasClaims` (`sub`, `username`, `iss`, `aud`, `jti`, `exp - iat == 900s`, header `kid`); `tokenSignedByOtherKeyIsRejected`.
  - `JwksControllerIT`: `jwksExposesOnlyPublicKey` (không có `d`, `p`, `q`); `tokenFromIssuerVerifiesAgainstServedJwks`.
  - `LoginIT`: `loginSuccessReturnsBearerToken`; `unknownEmailAndWrongPasswordReturnIdenticalBodies` (so sánh status và JSON giống hệt, trừ `requestId`/`instance`); `forgedTokensGet401OnProtectedEndpoint` cho `alg=none`, ký bằng khóa khác, hết hạn (`Clock` cố định), sai `aud` — dùng `GET /api/v1/users/me` (tạm trả 404/401, chỉ cần khác 200).
- [ ] **Step 2: Chạy** → Expected: FAIL.
- [ ] **Step 3: Implement** theo Interfaces; `SecurityConfig` chuyển sang `oauth2ResourceServer(jwt)` với `JwtDecoder` ở trên và `AuthenticationEntryPoint`/`AccessDeniedHandler` dùng `ProblemBodies` (`UNAUTHENTICATED`/`PERMISSION_DENIED`). Test dùng cấu hình `app.jwt.private-key-path` trỏ tới khóa sinh runtime trong thư mục tạm.
- [ ] **Step 4: Chạy lại** → Expected: PASS.
- [ ] **Step 5: Commit** `feat(identity): JWT RS256, JWKS, đăng nhập FR-002`.

### Task 11: Domain và persistence của refresh token

**Files:**
- Create: `.../domain/token/{RefreshToken,TokenHash,RefreshTokenRepository,RefreshTokenRotationService,RotationDecision}.java`, `.../infrastructure/persistence/token/{RefreshTokenJpaEntity,RefreshTokenDao,RefreshTokenMapper,RefreshTokenRepositoryImpl}.java`, `.../application/port/RefreshTokenGenerator.java`, `.../infrastructure/security/SecureRandomRefreshTokenGenerator.java`
- Test: `.../domain/token/{TokenHashTest,RefreshTokenRotationServiceTest}.java`, `.../infrastructure/persistence/token/RefreshTokenRepositoryImplIT.java`

**Interfaces:**
- Produces:
  - `record TokenHash(String value)` (64 ký tự hex) + `static TokenHash ofRaw(String raw)` = SHA-256 hex.
  - `final class RefreshToken`: `static RefreshToken issue(UUID id, UserId userId, UUID familyId, TokenHash hash, Instant now, Duration ttl, String userAgent, String ip)`; `rehydrate(...)`; getter `id, userId, familyId, tokenHash, expiresAt, revokedAt, replacedById, createdAt`; `boolean isRevoked()`, `boolean isExpired(Instant now)`.
  - `sealed interface RotationDecision permits Accept, Reuse, Expired` (record lồng nhau; `Reuse(UUID familyId)`); `final class RefreshTokenRotationService { RotationDecision decide(RefreshToken presented, Instant now) }` — đã revoke → `Reuse`; chưa revoke nhưng hết hạn → `Expired`; còn lại `Accept`.
  - `interface RefreshTokenRepository { RefreshToken save(RefreshToken t); Optional<RefreshToken> findByHash(TokenHash h); boolean revokeIfActive(UUID id, UUID replacedById, Instant now); int revokeFamily(UUID familyId, Instant now); }` — `revokeIfActive` là câu `UPDATE ... SET revoked_at, replaced_by_id WHERE id = ? AND revoked_at IS NULL`, trả `true` khi đúng 1 dòng bị đổi.
  - `record GeneratedRefreshToken(String raw, TokenHash hash)`; `interface RefreshTokenGenerator { GeneratedRefreshToken generate(); }` (32 byte `SecureRandom`, base64url không padding).

- [ ] **Step 1: Viết test**:
  - `TokenHashTest.ofRawIsDeterministicSha256` (input `"abc"` → hex SHA-256 chuẩn `ba7816bf...15ad`).
  - `RefreshTokenRotationServiceTest`: `activeTokenIsAccepted`; `revokedTokenIsReuse` (kèm `familyId`); `expiredButNotRevokedIsExpired`.
  - `RefreshTokenRepositoryImplIT`: `savesAndFindsByHash`; `revokeIfActiveTrueOnceThenFalse` (lần 1 `true`, lần 2 `false`); `revokeFamilyRevokesAllActiveTokensOfFamilyOnly` (token của family khác không đổi); `twoThreadsRevokeSameTokenExactlyOneWins`.
- [ ] **Step 2: Chạy** → Expected: FAIL.
- [ ] **Step 3: Implement** theo Interfaces (Flyway đã có bảng từ `V1`). Domain không import Spring/JPA.
- [ ] **Step 4: Chạy lại** → Expected: PASS.
- [ ] **Step 5: Commit** `feat(identity): domain và persistence refresh token, rotation service`.

### Task 12: Phát hành, refresh, logout (FR-002..FR-004)

**Files:**
- Create: `.../application/auth/{RefreshTokenUseCase,LogoutUseCase,RefreshResult,RefreshTokenReusedException,InvalidRefreshTokenException}.java`, `.../interfaces/rest/RefreshCookies.java`
- Modify: `.../application/auth/{LoginUseCase,LoginResult}.java`, `.../interfaces/rest/AuthController.java`, `.../resources/application.yml` (`app.cors.allowed-origins`, `app.refresh.ttl=30d`, `app.cookie.secure=true`)
- Test: `.../application/auth/{LoginUseCaseTest,RefreshTokenUseCaseTest}.java`, `.../interfaces/rest/RefreshFlowIT.java`

**Interfaces:**
- Consumes: Task 10–11.
- Produces:
  - `LoginUseCase` giờ tạo `RefreshToken` mới với `familyId` mới, lưu hash, trả `LoginResult.refreshTokenRaw` và `refreshExpiresAt` (+30 ngày).
  - `RefreshTokenUseCase#execute(String rawToken, String userAgent, String ip): RefreshResult(AccessToken, String refreshTokenRaw, Instant refreshExpiresAt)` `@Transactional`: tra theo `TokenHash.ofRaw`; không có → `InvalidRefreshTokenException`; `decide` → `Reuse`: `revokeFamily` rồi ném `RefreshTokenReusedException`; `Expired` → `InvalidRefreshTokenException`; `Accept`: phát token mới cùng `familyId`, `revokeIfActive(old, new.id, now)`; nếu trả `false` (thua cuộc đua) → coi là reuse như trên. Lưu ý: ném exception trong transaction sẽ rollback `revokeFamily`; do đó `revokeFamily` phải chạy trong **transaction riêng** (`REQUIRES_NEW` đặt trên một bean/ method của use case riêng `RevokeFamilyCommand` hoặc dùng `TransactionTemplate`), và có test chứng minh family vẫn bị revoke sau khi request trả 401.
  - `LogoutUseCase#execute(String rawTokenOrNull)`: thu hồi token hiện tại nếu có và còn hiệu lực; im lặng nếu thiếu/không tồn tại.
  - REST: `POST /api/v1/auth/login` đặt cookie `nexus_rt` (thuộc tính ở Global Constraints, `Max-Age=2592000`); `POST /api/v1/auth/refresh` đọc cookie, trả `200 {accessToken,tokenType,expiresIn}` + cookie mới; lỗi `InvalidRefreshTokenException` → `401 UNAUTHENTICATED`, `RefreshTokenReusedException` → `401 REFRESH_TOKEN_REUSED` (đều kèm cookie xóa `Max-Age=0`); `POST /api/v1/auth/logout` → `204` + xóa cookie. Nếu header `Origin` có mặt trên `/auth/refresh` mà không thuộc `app.cors.allowed-origins` → `403 PERMISSION_DENIED`.

- [ ] **Step 1: Viết test**:
  - `LoginUseCaseTest.issuesRefreshTokenWithNewFamilyAndStoresOnlyHash`.
  - `RefreshTokenUseCaseTest` (mock repo/port): `acceptedTokenIsRotatedWithSameFamily`; `reuseRevokesFamilyAndThrowsReused`; `lostRaceOnRevokeIfActiveIsTreatedAsReuse`.
  - `RefreshFlowIT`: `loginSetsHttpOnlyStrictCookieScopedToAuthPath`; `refreshRotatesAndOldTokenBecomesUnusable`; `reusingOldTokenReturns401ReusedAndRevokesWholeFamily` (sau khi token cũ bị dùng lại, token mới của family cũng refresh → `401`, kiểm tra DB mọi token của family có `revoked_at`); `refreshWithoutCookieOrGarbageCookieReturns401Unauthenticated` (thiếu cookie, chuỗi rác, token hợp lệ nhưng đã hết hạn do `Clock` dịch 31 ngày); `logoutRevokesTokenAndIsIdempotent` (gọi 2 lần đều `204`); `refreshWithForeignOriginReturns403`.
- [ ] **Step 2: Chạy** → Expected: FAIL.
- [ ] **Step 3: Implement** theo Interfaces.
- [ ] **Step 4: Chạy lại** → Expected: PASS.
- [ ] **Step 5: Commit** `feat(identity): refresh rotation, reuse detection, logout FR-003 FR-004`.

### Task 13: Profile (FR-006, FR-007, FR-010)

**Files:**
- Create: `.../application/user/{GetProfileQuery,UpdateProfileUseCase,UserNotFoundException,PublicProfile}.java`, `.../interfaces/rest/{UserController,PublicProfileResponse,UpdateProfileRequest}.java`
- Test: `.../application/user/UpdateProfileUseCaseTest.java`, `.../interfaces/rest/UserControllerIT.java`

**Interfaces:**
- Consumes: `UserRepository`, `User.updateProfile`, `DomainEventPublisher`.
- Produces:
  - `record PublicProfile(UUID id, String username, String displayName, String avatarUrl, String aboutMe)`.
  - REST (đều cần Bearer): `GET /api/v1/users/me` → `MeResponse`; `PATCH /api/v1/users/me` body `{displayName?, aboutMe?}` (chỉ cập nhật field có mặt) → `200 MeResponse`, phát `UserProfileUpdated`; xung đột phiên bản → `409 VERSION_CONFLICT`; `GET /api/v1/users/{userId}` → `PublicProfileResponse` (`404 NOT_FOUND` nếu không có hoặc id không phải UUID thì `400`); `GET /api/v1/users?ids=a,b` (≤ 100, bỏ trùng; rỗng → `200 []`; > 100 hoặc id không phải UUID → `400 VALIDATION_FAILED`); `GET /api/v1/users/by-username/{username}` (chuẩn hóa chữ thường) → `PublicProfileResponse` hoặc `404`.
  - Public profile **không** có `email`.

- [ ] **Step 1: Viết test**:
  - `UpdateProfileUseCaseTest`: `updatesOnlyProvidedFieldsAndPublishesEvent`.
  - `UserControllerIT`: `meRequiresBearer` (không token → `401 UNAUTHENTICATED`); `patchMeUpdatesProfileAndWritesOutboxRow` (`routing_key='user.profile-updated'`); `patchWithTooLongAboutMeReturns400` (191 ký tự); `publicProfileHidesEmail`; `batchLookupRejectsMoreThan100AndBadUuidAndAcceptsEmpty`; `batchLookupDeduplicatesIds`; `byUsernameIsCaseInsensitive`; `concurrentPatchesOneGets409VersionConflict`.
- [ ] **Step 2: Chạy** → Expected: FAIL.
- [ ] **Step 3: Implement** theo Interfaces; `sub` của JWT là `userId`, lấy qua `Authentication`/`@AuthenticationPrincipal Jwt`.
- [ ] **Step 4: Chạy lại** → Expected: PASS.
- [ ] **Step 5: Commit** `feat(identity): profile FR-006 FR-007 FR-010`.

### Task 14: Avatar Cloudinary (FR-008, FR-100, FR-102)

**Files:**
- Create: `.../application/port/UploadSigner.java`, `.../application/user/{IssueAvatarSignatureUseCase,ChangeAvatarUseCase,InvalidMediaException}.java`, `.../infrastructure/cloudinary/{CloudinaryUploadSigner,CloudinaryProperties}.java`, `.../interfaces/rest/{AvatarSignatureResponse,ChangeAvatarRequest}.java`
- Modify: `.../interfaces/rest/UserController.java`, `.../resources/application.yml`
- Test: `.../infrastructure/cloudinary/CloudinaryUploadSignerTest.java`, `.../application/user/ChangeAvatarUseCaseTest.java`, `.../interfaces/rest/AvatarIT.java`

**Interfaces:**
- Produces:
  - `record UploadSignature(String cloudName, String apiKey, long timestamp, String signature, String folder, String uploadPreset)`; `interface UploadSigner { UploadSignature sign(String folder, Instant now); String publicUrl(String publicId); }`.
  - `CloudinaryUploadSigner` (không dùng SDK Cloudinary, ponytail: HMAC của một chuỗi là đủ): chuỗi cần ký là các tham số `folder`, `timestamp`, `upload_preset` sắp xếp theo tên, nối `k=v` bằng `&`, cộng `CLOUDINARY_API_SECRET`, rồi SHA-1 hex (theo tài liệu Cloudinary "Generating authentication signatures"; đối chiếu bằng ví dụ chính thức ở bước test). `publicUrl(publicId)` = `https://res.cloudinary.com/{cloudName}/image/upload/{publicId}` (lựa chọn của tôi; không gọi Admin API ở P1 — định dạng/kích thước được ép bởi upload preset signed `nexus_avatar`; nâng cấp kiểm tra bằng Admin API nếu cần).
  - Cấu hình qua env: `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`, `CLOUDINARY_UPLOAD_PRESET` (= `CLOUDINARY_UPLOAD_PRESET_AVATAR`), `CLOUDINARY_FOLDER_ROOT`.
  - `POST /api/v1/users/me/avatar/signature` → `200 AvatarSignatureResponse` (folder cố định `${root}/avatars/{userId}`, **không bao giờ** trả secret).
  - `PUT /api/v1/users/me/avatar` body `{publicId}`: hợp lệ khi bắt đầu bằng `${root}/avatars/{userId}/`, không chứa `..`, không chứa ký tự ngoài `[A-Za-z0-9/_-.]`, dài ≤ 255; không hợp lệ → `400 VALIDATION_FAILED`. Hợp lệ → `User.changeAvatar(MediaRef(publicId, publicUrl))`, phát `UserProfileUpdated`, trả `MeResponse`.

- [ ] **Step 1: Viết test**:
  - `CloudinaryUploadSignerTest.signatureMatchesCloudinaryDocExample` (dùng ví dụ trong tài liệu Cloudinary: tham số mẫu + secret mẫu → SHA-1 mẫu; sao nguyên giá trị từ trang tài liệu vào test); `signatureChangesWithFolder`; `neverContainsSecretInResponse`.
  - `ChangeAvatarUseCaseTest` (signer giả): `rejectsOtherUsersPublicId`; `rejectsPathTraversal`; `savesMediaRefWithPublicUrl`.
  - `AvatarIT`: `signatureEndpointReturnsFolderBoundToCaller`; `putAvatarWithOtherUserFolderReturns400`; `putAvatarWithDotDotReturns400`; `putAvatarStoresAndWritesProfileUpdatedOutbox`.
- [ ] **Step 2: Chạy** → Expected: FAIL.
- [ ] **Step 3: Implement** theo Interfaces.
- [ ] **Step 4: Chạy lại** → Expected: PASS.
- [ ] **Step 5: Kiểm tra thủ công một lần với Cloudinary thật** (cần `.env` có khóa thật và hai upload preset signed `nexus_avatar`, `nexus_attachment` theo [infra-structure.md §4](../../04-project-structure/infra-structure.md#4-biến-môi-trường-và-secret)): lấy chữ ký từ endpoint, upload một ảnh bằng `curl` tới `https://api.cloudinary.com/v1_1/<cloud>/image/upload` → Expected: `200` có `public_id` bắt đầu bằng `nexus-dev/avatars/<userId>/`; sau đó `PUT /users/me/avatar` với `public_id` đó → `200`. Ghi kết quả vào `docs/journal.md`.
- [ ] **Step 6: Commit** `feat(identity): avatar qua Cloudinary signed upload FR-008 FR-100 FR-102`.

### Task 15: Docker, Compose app, CI, demo, đóng phase

**Files:**
- Create: `services/identity-service/Dockerfile`, `infra/compose.app.yaml`, `.github/workflows/identity-service.yml`, `docs/journal.md`, `infra/scripts/demo-p1.sh`
- Modify: `compose.yaml` (thêm `infra/compose.app.yaml` vào `include`), `README.md`, `.env.example` (thêm biến Cloudinary giả, `JWT_KEY_ID`, `CORS_ALLOWED_ORIGINS`)

**Interfaces:**
- Produces: service compose `identity-service` (image `ghcr.io/quocanh112233/nexus-identity-service:${TAG:-latest}`, `build.context: ..`, `dockerfile: services/identity-service/Dockerfile`), env: `SPRING_PROFILES_ACTIVE=docker`, `DB_URL=jdbc:postgresql://postgres:5432/nexus?currentSchema=identity`, `DB_USER=identity_svc`, `DB_PASSWORD`, `JWT_PRIVATE_KEY_PATH=/run/secrets/jwt_private_key`, `JWT_KEY_ID`, các biến `CLOUDINARY_*`, `CORS_ALLOWED_ORIGINS`; `secrets` từ `infra/secrets/jwt-private.pem`; cổng publish `8081:8081`; `depends_on` postgres `service_healthy`; `deploy.resources.limits.memory: 512m`.
- Workflow `identity-service.yml` là bản mỏng gọi `_backend-service.yml` (`module: services/identity-service`, `image: nexus-identity-service`) với path filter `services/identity-service/**`, `libs/**`, `pom.xml`, hai file workflow, theo mẫu [devops-plan.md §3.1](../../05-plan/devops-plan.md#31-workflow-theo-service--path-filter).
- `demo-p1.sh`: đăng ký → đăng nhập → gọi `/users/me` → refresh → dùng lại refresh cũ (thấy `REFRESH_TOKEN_REUSED`), in kết quả từng bước; thoát non-zero nếu bước nào sai.

- [ ] **Step 1: Viết `Dockerfile`** theo mẫu [backend-structure.md §6](../../04-project-structure/backend-structure.md#6-dockerfile-mỗi-service-adr-007), chỉnh: bỏ các dòng `COPY proto/...` (P1 chưa có module proto), `COPY libs/` giữ, `EXPOSE 8081`, healthcheck `http://localhost:8181/actuator/health/readiness`. Nếu `java -Djarmode=tools ... extract` hoặc launcher của Boot 4.1 khác mẫu, theo mục "Efficient container images" trong reference Boot 4.1.
- [ ] **Step 2: Build và chạy** `docker compose up -d --build --wait` → Expected: `identity-service` `healthy`.
- [ ] **Step 3: Kiểm tra container**: `docker exec <identity-container> id` → uid `10001` (không root); `docker images` kích thước < 250 MB; sửa một file Java rồi `docker compose build identity-service` → chỉ layer `application` build lại (xem log "CACHED").
- [ ] **Step 4: Chạy** `infra/scripts/demo-p1.sh` → Expected: exit 0, thấy đủ 5 bước kể cả `REFRESH_TOKEN_REUSED`.
- [ ] **Step 5: Chạy** `docker compose down -v && docker compose up -d --build --wait && infra/scripts/demo-p1.sh` (máy sạch) → Expected: exit 0.
- [ ] **Step 6: Viết workflow** `identity-service.yml`; kiểm tra bằng `actionlint` (như Task 3).
- [ ] **Step 7: Cập nhật docs**: `README.md` (chạy bằng `docker compose up -d --build`, bảng endpoint P1, Swagger UI `:8081/swagger-ui.html`), `docs/journal.md` (3-5 dòng đã học: schema isolation, DDD 4 lớp + DAO, refresh rotation/reuse, race condition khi đăng ký, Docker layer cache), đánh dấu P0/P1 xong trong `docs/05-plan/backend-plan.md` (đổi `[ ]` thành `[x]` các mục đã làm).
- [ ] **Step 8: Chạy toàn bộ** `./mvnw -B verify` → Expected: `BUILD SUCCESS`, `ArchitectureTest` xanh.
- [ ] **Step 9: Commit** `feat(identity): Dockerfile, compose, CI, demo P1` rồi tag `git tag v0.1.0`. **Không push**; báo người dùng việc còn lại cần họ làm: push nhánh, mở PR, xác nhận CI xanh và image xuất hiện trên GHCR.

---

## Self-review

- **Phủ spec:** P0 (6 mục backend-plan) → Task 1–3, trừ `definitions.json` RabbitMQ hoãn có chủ đích. P1 (13 mục) → Task 4 (#10), 6 (#1, #11), 7 (#2), 8 (#3), 9 (#4, #9), 10 (#5), 11–12 (#6), 13 (#7), 14 (#8), 15 (#12, #13). FR-001..004, 006..008, 010, 100, 102 đều có test. FR-005, FR-009 là Phase 2, không làm.
- **Lệch so với tài liệu (đã nêu tại chỗ):** module `libs` tạo theo nhu cầu; RabbitMQ chỉ user admin ở P0; Spring Boot 4.1 thay 3.4; outbox viết vào `nexus-common-messaging` từ P1 (relay P5); avatar không gọi Admin API ở P1; quy tắc `Username`; JaCoCo gate 70% để P9.
- **Nhất quán tên:** `OutboxWriter`/`OutboxMessage`, `DomainEventPublisher`, `UserRepository`, `RefreshTokenRepository.revokeIfActive/revokeFamily`, `TokenHash.ofRaw`, `LoginResult` (Task 10 chỉ có access token, Task 12 thêm `refreshTokenRaw`, `refreshExpiresAt`) dùng thống nhất từ Task 7 đến 15.
- **Rủi ro cần xác nhận khi thực thi (tên artifact/annotation của Boot 4.1 và Testcontainers 2.x, springdoc cho Boot 4, Jackson 3):** Task 4, 5, 6 có bước tra BOM/start.spring.io; nếu tên khác với gợi ý trong plan thì theo BOM và ghi lại vào pom.
