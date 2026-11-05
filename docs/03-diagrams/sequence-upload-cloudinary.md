# Sequence: upload file qua Cloudinary signed upload

File này mô tả cách frontend upload thẳng lên Cloudinary bằng chữ ký do backend cấp, rồi gửi tin nhắn có đính kèm (WF-09, FR-058, FR-101, FR-102). Avatar giống hệt nhưng gọi Identity (`/api/v1/users/me/avatar/signature`, `PUT /api/v1/users/me/avatar`).

```mermaid
sequenceDiagram
    autonumber
    actor U as Người dùng
    participant FE as Frontend
    participant GW as api-gateway
    participant MS as messaging-service
    participant CM as community-service
    participant CLD as Cloudinary

    U->>FE: chọn file ảnh 2MB
    FE->>FE: kiểm tra sơ bộ loại, kích thước
    FE->>GW: POST /api/v1/attachments/signature channelId C
    GW->>MS: chuyển tiếp
    MS->>CM: gRPC CheckChannelPermissions, hoặc dùng cache
    CM-->>MS: có ATTACH_FILES
    MS->>MS: folder nexus/attachments/C/userId, timestamp, ký SHA-256 bằng API secret
    MS-->>FE: cloudName, apiKey, timestamp, folder, signature, uploadPreset
    FE->>CLD: POST multipart /v1_1/cloudName/auto/upload kèm chữ ký
    CLD->>CLD: kiểm tra chữ ký, preset giới hạn định dạng và dung lượng
    CLD-->>FE: publicId, secureUrl
    FE->>GW: POST /api/v1/channels/C/messages content, nonce, attachments publicId
    GW->>MS: chuyển tiếp
    MS->>MS: publicId có đúng tiền tố nexus/attachments/C/userId không
    MS->>CLD: Admin API lấy metadata thật bytes, format
    CLD-->>MS: metadata
    MS->>MS: lưu message và message_attachments như luồng gửi tin
    MS-->>FE: 201 message có attachments
```

API secret không bao giờ rời backend. Lỗi tại bước kiểm tra tiền tố → `400 VALIDATION_FAILED`.
