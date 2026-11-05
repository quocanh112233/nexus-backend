# Nhật ký học (P0-P1)

- Cách ly schema bằng user DB riêng: mỗi service chỉ thấy schema của mình, `permission denied` ở mức DB mạnh hơn quy ước trong code.
- DDD 4 lớp + DAO: `domain` thuần Java, JPA entity và mapper nằm ở `infrastructure/persistence`; ArchUnit giữ ranh giới này.
- Refresh token rotation: mỗi lần refresh phát token mới và thu hồi token cũ; dùng lại token cũ thì thu hồi cả family và trả `REFRESH_TOKEN_REUSED`.
- Race condition khi đăng ký: kiểm tra trùng trước rồi insert không đủ, phải dựa unique constraint và bắt lỗi vi phạm để trả `409`.
- Docker layer cache: copy POM trước source, `dependency:go-offline` thành layer riêng; sửa code chỉ build lại layer `application` (layer dependencies báo `CACHED`).

## Việc còn lại của người dùng

- Điền `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` trong `.env`.
- Tạo 2 upload preset chế độ Signed trên Cloudinary: `nexus_avatar` và `nexus_attachment`.
- Chạy thủ công kiểm tra upload avatar thật (Step 5 của Task 14, đã bỏ qua vì cần key thật): xin chữ ký, upload lên Cloudinary, `PUT /users/me/avatar`; ghi kết quả vào đây: _chưa chạy_.
- Push nhánh, mở PR, xác nhận CI xanh và image `ghcr.io/quocanh112233/nexus-identity-service` xuất hiện trên GHCR.
- Image identity-service nặng ~270MB filesystem (415MB theo `docker images`), vượt mục tiêu <250MB; chấp nhận vì base bắt buộc `eclipse-temurin:21-jre-alpine` đã ~212MB, jlink/JRE tùy biến hoãn sang P9.
