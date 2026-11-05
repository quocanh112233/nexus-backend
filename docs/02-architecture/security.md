# Bảo mật

File này mô tả cơ chế xác thực, phân quyền, validate tại trust boundary, CORS, rate limit, bảo mật nội bộ và chữ ký Cloudinary.

## 1. Trust boundary

| Boundary | Vào từ | Kiểm soát |
|---------|--------|-----------|
| Internet → api-gateway | Trình duyệt | TLS (Caddy ở demo), CORS, rate limit, verify JWT |
| api-gateway → service (REST) | Gateway | Service **verify JWT lại** (không tin header từ gateway); validate body |
| Client → realtime-gateway (WS) | Trình duyệt qua gateway | JWT ở `CONNECT`, kiểm tra quyền ở `SUBSCRIBE`/`SEND` |
| Service → service (gRPC) | Mạng Docker nội bộ | JWT user trong metadata; call không có user context dùng `x-service-token` + allowlist RPC (P-36); validate request; cổng gRPC không publish ra host (trừ profile local debug) |
| Broker → consumer | RabbitMQ | Mỗi service một user RabbitMQ chỉ có quyền trên exchange/queue của mình; validate payload; event lạ → DLQ |
| Service → Postgres/Redis | | User DB riêng mỗi schema; Redis ACL theo prefix |

## 2. Xác thực (JWT + refresh token) — P-09

| Thành phần | Chi tiết |
|-----------|---------|
| Ký | RS256, khóa riêng chỉ ở Identity (`JWT_PRIVATE_KEY_PATH` từ secret), `kid` trong header để xoay khóa |
| Verify | Spring Security OAuth2 Resource Server, `jwk-set-uri: http://identity-service:8081/.well-known/jwks.json` (cache) |
| Access token | 15 phút, giữ trong bộ nhớ frontend (không localStorage → giảm rủi ro XSS đánh cắp lâu dài) |
| Refresh token | 256-bit ngẫu nhiên, DB lưu SHA-256, 30 ngày, cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` |
| Rotation | Mỗi lần refresh: revoke token cũ, cấp token mới cùng `family_id` |
| Reuse detection | Token đã revoke bị dùng lại → revoke cả family, trả `REFRESH_TOKEN_REUSED` |
| Password | BCrypt cost 12; thông báo lỗi đăng nhập chung chung; rate limit `/auth/**` |
| CSRF | API dùng Bearer header (không cookie) → không bị CSRF; endpoint `/auth/refresh` dùng cookie nhưng `SameSite=Strict` + kiểm tra `Origin` |

## 3. Phân quyền

- Xác thực ở gateway + service; **phân quyền nghiệp vụ ở service sở hữu dữ liệu** (Community tính, Messaging hỏi qua gRPC). Gateway không quyết định quyền nghiệp vụ.
- Mô hình: [permission-model.md](../01-requirements/permission-model.md).
- `404` thay `403` khi tài nguyên không được thấy (không lộ sự tồn tại của kênh riêng tư).

## 4. Validate tại trust boundary

| Nơi | Cách |
|----|------|
| REST body/param | Bean Validation (`@Valid`, `@Size`, `@Pattern`) ở DTO `interfaces/rest`; value object domain validate lần nữa (bất biến) |
| STOMP | Kiểm tra destination bằng pattern cố định; id phải là UUID |
| gRPC | Validate trong server adapter (UUID, list ≤ 100) → `INVALID_ARGUMENT` |
| Event | Consumer DTO validate; thiếu field bắt buộc → DLQ |
| Nội dung tin | Lưu nguyên văn, frontend render dạng text (React escape) — **không** render HTML từ user |
| Giới hạn | Content ≤ 2000, attachment ≤ 5, mention ≤ 20, body request ≤ 64 KB |

## 5. CORS, header, rate limit

| Hạng mục | Cấu hình |
|---------|---------|
| CORS | Chỉ ở api-gateway; origin cho phép từ env `CORS_ALLOWED_ORIGINS` (local `http://localhost:3000`); `allowCredentials=true` cho `/auth/**` |
| Header bảo mật | `X-Content-Type-Options: nosniff`, `Referrer-Policy`, frontend thêm CSP cơ bản |
| Rate limit (NFR-013) | Spring Cloud Gateway `RequestRateLimiter` + Redis: auth theo IP 10/phút; API theo `sub` 100/phút; gửi tin 5/5 s; trả `429` + `Retry-After` |
| WebSocket | Giới hạn kích thước frame 64 KB; typing throttle |

## 6. Cloudinary signed upload

| Rủi ro | Biện pháp |
|-------|----------|
| Lộ API secret | Secret chỉ ở Identity/Messaging (env); frontend chỉ nhận `signature`, `apiKey`, `timestamp` |
| Upload tùy ý vào account | Chỉ ký khi đã xác thực và (đính kèm) có `ATTACH_FILES`; chữ ký gắn `folder` cố định do server chọn; dùng **upload preset signed** giới hạn `allowed_formats`, `max_file_size` |
| Gắn file người khác | Khi lưu, kiểm tra `publicId` bắt đầu bằng `nexus/avatars/{userId}/` hoặc `nexus/attachments/{channelId}/{userId}/` |
| Giả mạo metadata | Backend lấy `bytes`, `format`, `resource_type` từ Cloudinary Admin API (không tin client) |
| Chữ ký dùng lại lâu | Cloudinary từ chối chữ ký có `timestamp` quá 1 giờ |
| File nguy hiểm | Chỉ cho phép danh sách định dạng; file không phải ảnh hiển thị dạng link tải, không inline |

## 7. Bí mật

`.env` không commit (chỉ `.env.example`); CI dùng GitHub Secrets; khóa JWT sinh bằng script `infra/scripts/gen-jwt-keys.sh` cho local. Chi tiết: [infra-structure.md](../04-project-structure/infra-structure.md#4-biến-môi-trường-và-secret).

## 8. Container

Image chạy non-root, base image tối giản, quét Trivy trong CI (NFR-017, [ADR-007](../00-overview/adr/ADR-007-docker-packaging.md)).
