# Rủi ro và câu hỏi mở

File này liệt kê rủi ro dự án kèm cách giảm thiểu, và các câu hỏi cần người dùng quyết định trước hoặc trong lúc triển khai.

## 1. Rủi ro kỹ thuật

| # | Rủi ro | Khả năng | Ảnh hưởng | Giảm thiểu |
|---|-------|---------|----------|-----------|
| R1 | 7 JVM + hạ tầng ngốn RAM máy local/VM | Cao | Trung bình | Giới hạn heap (NFR-005), chỉ bật observability khi cần, `docker compose up` một phần |
| R2 | Microservice làm khó debug luồng xuyên service | Cao | Trung bình | Trace từ P4, `requestId` trong mọi log, Grafana từ P2 |
| R3 | Eventual consistency gây lỗi "đọc-sau-ghi" (vừa tạo kênh đã gửi tin) | Trung bình | Thấp | Fallback gRPC ghi `channel_refs` (xem [sequence-create-server.md](../03-diagrams/sequence-create-server.md)) |
| R4 | Thứ tự event đảo, event trùng | Trung bình | Trung bình | Inbox, `GREATEST`, `version` (xem [messaging-deep-dive.md](../02-architecture/messaging-deep-dive.md#7-ordering-thứ-tự)) |
| R5 | Cache quyền stale sau khi đổi role | Trung bình | Thấp | Version key qua event + TTL 60 s |
| R6 | Rò rỉ realtime sau khi mất quyền (vài trăm ms) | Thấp | Thấp | Thu hồi subscription theo event; chấp nhận ghi rõ |
| R7 | Projection quan hệ của Presence lệch so với Community/Messaging (event mất/trễ) → người vừa rời server vẫn thấy presence một lúc | Thấp | Trung bình (riêng tư — D-18) | Thu hồi subscription `/topic/servers/{id}` ngay khi nhận event kick/ban/rời; `loaded` TTL 24 h tự nạp lại; IT riêng tư bắt buộc (NFR-018) |
| R12 | Race giữa ban và join qua invite để lại member bị ban | Trung bình | Trung bình | Advisory lock theo cặp `(server, user)` (WF-12), IT đồng thời |
| R8 | Spring gRPC còn mới, thay đổi API | Trung bình | Thấp | Thay thế `net.devh` đã ghi (P-10); adapter cô lập trong `infrastructure/grpc` |
| R9 | Free tier Cloudinary/VM thay đổi điều khoản | Thấp | Trung bình | Không phụ thuộc tính năng đặc thù; có thể đổi nhà cung cấp |
| R10 | Testcontainers làm CI chậm | Trung bình | Thấp | Singleton container, path filter, cache |
| R11 | `libs/` phình to thành coupling ngầm | Trung bình | Trung bình | Luật ở [backend-structure.md](../04-project-structure/backend-structure.md#5-module-dùng-chung-và-tránh-coupling), review mỗi PR đổi `libs/` |

## 2. Rủi ro "làm quá nhiều không xong"

Đây là rủi ro **lớn nhất** của dự án một người, không deadline, nhiều service.

| Dấu hiệu | Phản ứng |
|---------|---------|
| Một phase kéo dài, không demo được | Chia nhỏ lại phase; cắt FR phụ của phase đó; chỉ cần demo luồng chính |
| Muốn thêm tính năng ngoài MVP | Ghi vào backlog ([vision-and-goals.md](../00-overview/vision-and-goals.md#5-backlog-đề-xuất-không-thuộc-mvp-chờ-người-dùng-duyệt)), **không** làm trước M3 |
| Sa đà làm đẹp frontend | Nhắc lại P-30: chỉ đủ demo |
| Muốn "làm cho chuẩn" Kafka/K8s/service mesh/CQRS | Để sau M4 ở [mục mở rộng tùy chọn](learning-path.md#mở-rộng-tùy-chọn) |
| Mất động lực giữa chừng | Mỗi phase đều có tag + demo → luôn có phiên bản trình bày được |

**Thứ tự cắt phạm vi** (cắt từ trên xuống; không cắt phần "giữ"):

| Cắt trước | Hạng mục | Ảnh hưởng CV |
|----------|---------|-------------|
| 1 | Đính kèm file trong tin (FR-058, FR-101) — giữ avatar | Rất nhỏ |
| 2 | Notification service (P8) | Mất 1 ví dụ consumer thuần event (đã có Presence) |
| 3 | Social & DM (P7) | Nhỏ |
| 4 | Demo công khai | Thay bằng video 2-3 phút |
| 5 | k6 WebSocket fan-out | Giữ k6 REST |
| **Giữ** | P0-P6 + test CI + observability + README + ADR | Lõi câu chuyện: microservice, gRPC, message queue, realtime |

## 3. Câu hỏi mở cần người dùng quyết định

**Chỉ còn một câu hỏi mở:**

| # | Câu hỏi | Đề xuất mặc định | Mã |
|---|--------|------------------|----|
| Q11 | Nơi deploy demo: Oracle Always Free, VPS trả phí (~5-10 USD), hay chỉ quay video? **Chưa quyết định — có thể hoãn tới P9** vì mọi phase trước chỉ chạy local bằng Docker Compose, thiết kế không phụ thuộc lựa chọn này (chỉ ảnh hưởng: build thêm image `arm64` nếu chọn Oracle ARM) | Quyết định ở đầu P9 | P-28 |

**Đề xuất kỹ thuật còn chờ duyệt** (không chặn việc bắt đầu P0; mặc định áp dụng như [decisions.md](../00-overview/decisions.md) nếu người dùng không phản đối):

| Mã | Nội dung ngắn |
|----|--------------|
| P-05 | Friend + DM nằm trong Messaging |
| P-06 | Typing không đi qua Presence |
| P-07, P-08, P-09 | Java 21 / Spring Boot 4.1; UUIDv7; JWT RS256 + refresh token rotation |
| P-11 | Proto đặt ở `nexus-backend/proto/` |
| P-13 | STOMP + queue riêng mỗi instance realtime-gateway |
| P-15, P-16 | DM chỉ giữa bạn bè; reaction chỉ emoji Unicode |
| P-17..P-25 | Spring Cloud Gateway, stack frontend, Dockerfile layered, otel-lgtm, chiến lược test, quy ước commit, DNS discovery, Resilience4j, projection cục bộ |
| P-27..P-32 | OpenAPI, nơi/cách deploy demo (gắn Q11), Redis 3 vai trò, backend-first, k6, chi tiết Docker/GHCR/Trivy |
| P-35 | Kiểm tra ban khi join không cần RPC |

**Đã trả lời / đã duyệt (lưu vết):**

| # | Nội dung | Kết quả |
|---|---------|--------|
| Q1-Q7 | RabbitMQ, 2 repo, Maven, thứ tự thêm service, thư viện gRPC, kiểm tra quyền gRPC + Redis, chuyển docs vào `nexus-backend/docs/` | Người dùng duyệt: P-01, P-02, P-03, P-04, P-10, P-12, P-26 |
| Q10 | Presence | Người lạ không thấy (D-18); cách hiện thực P-14 đã duyệt |
| Q12 | Tài khoản Cloudinary | Đã có (D-20) |
| Q13 | Lombok | Không dùng (P-33 đã duyệt, ADR-008) |
| Q14 | Kick/ban | Vào MVP ở P3 (D-19); audit tối thiểu P-34 đã duyệt |
| Q15 | `x-service-token` cho gRPC không có user | P-36 đã duyệt |
| Q8, Q9 | Typing không qua Presence; DM chỉ bạn bè; emoji Unicode | Chuyển thành đề xuất chờ duyệt P-06, P-15, P-16 ở bảng trên |
