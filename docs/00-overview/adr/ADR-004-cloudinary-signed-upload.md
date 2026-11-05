# ADR-004: Upload trực tiếp Cloudinary bằng signed upload

Ghi lại cách xử lý avatar và file đính kèm mà không cần Media service.

- **Trạng thái:** Accepted (D-06, D-20)

## Bối cảnh
- Đẩy file qua backend tốn băng thông/bộ nhớ JVM, không phải trọng tâm học.

## Quyết định
- Frontend xin chữ ký: Identity `POST /api/v1/users/me/avatar/signature`, Messaging `POST /api/v1/attachments/signature`.
- Backend ký các tham số cố định do **server** quyết định (`folder`, `public_id` prefix, `timestamp`, `allowed_formats`, giới hạn kích thước qua upload preset). API secret không bao giờ rời backend.
- Frontend upload thẳng lên Cloudinary, rồi gửi `publicId` cho backend; backend kiểm tra `publicId` nằm trong folder đã cấp cho user đó trước khi lưu.
- DB chỉ lưu `public_id` + `secure_url` (+ metadata).

## Hệ quả
- (+) Không có Media service; backend nhẹ.
- (-) File có thể bị upload mà không dùng (mồ côi) → FR-103 Phase 2 dọn dẹp.
- (-) Phải xác minh `publicId` ở backend, nếu không user có thể gắn file của người khác.
Chi tiết bảo mật: [security.md](../../02-architecture/security.md#6-cloudinary-signed-upload).
