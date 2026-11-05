# Use case diagram

File này thể hiện tác nhân và use case theo module. Mermaid không có use case diagram chuẩn UML nên dùng `flowchart` mô phỏng: hình tròn kép = tác nhân, hình viên thuốc = use case, khung = module (bounded context). Mã FR trong ngoặc vuông.

## 1. Tác nhân

| Tác nhân | Mô tả |
|---------|-------|
| Khách | Chưa đăng nhập |
| Người dùng | Đã đăng nhập |
| Thành viên | Người dùng trong một server |
| Quản trị server | Thành viên có quyền quản lý (MANAGE_*), hoặc owner |
| Cloudinary | Hệ thống ngoài lưu file |

## 2. Identity

```mermaid
flowchart LR
    Guest((Khách))
    User((Người dùng))
    Cld((Cloudinary))
    subgraph Identity["Identity"]
        UC1(["Đăng ký - FR-001"])
        UC2(["Đăng nhập - FR-002"])
        UC3(["Làm mới token - FR-003"])
        UC4(["Đăng xuất - FR-004"])
        UC5(["Xem, sửa profile - FR-006, FR-007"])
        UC6(["Đổi avatar - FR-008, FR-100"])
        UC7(["Tra cứu username - FR-010"])
    end
    Guest --> UC1
    Guest --> UC2
    User --> UC3
    User --> UC4
    User --> UC5
    User --> UC6
    User --> UC7
    UC6 -.-> Cld
```

## 3. Community

```mermaid
flowchart LR
    User((Người dùng))
    Member((Thành viên))
    Admin((Quản trị server))
    subgraph Community["Community"]
        C1(["Tạo server - FR-020"])
        C2(["Xem server của tôi - FR-023"])
        C3(["Xem trước và tham gia qua invite - FR-027, FR-028"])
        C4(["Rời server - FR-030"])
        C5(["Xem thành viên - FR-031"])
        C6(["Tạo invite - FR-026"])
        C7(["Sửa, xóa server - FR-021, FR-022"])
        C8(["Quản lý category, kênh - FR-024, FR-025"])
        C9(["Quản lý role - FR-032"])
        C10(["Gán role - FR-033"])
        C11(["Overwrite quyền kênh - FR-034"])
        C12(["Thu hồi invite - FR-029"])
        C13(["Kick member - FR-036"])
        C14(["Ban, xem ban, gỡ ban - FR-037, FR-039"])
    end
    User --> C1
    User --> C2
    User --> C3
    Member --> C4
    Member --> C5
    Member --> C6
    Admin --> C7
    Admin --> C8
    Admin --> C9
    Admin --> C10
    Admin --> C11
    Admin --> C12
    Admin --> C13
    Admin --> C14
```

## 4. Messaging, Social, Presence, Notification

```mermaid
flowchart LR
    Member((Thành viên))
    User((Người dùng))
    Cld((Cloudinary))
    subgraph Messaging["Messaging"]
        M1(["Gửi tin, reply, mention - FR-050, FR-053, FR-059"])
        M2(["Sửa, xóa tin - FR-051, FR-052"])
        M3(["Reaction - FR-054"])
        M4(["Xem lịch sử - FR-055"])
        M5(["Nhận realtime, typing - FR-056, FR-057"])
        M6(["Đính kèm file - FR-058, FR-101"])
    end
    subgraph Social["Social và DM"]
        S1(["Gửi, hủy lời mời kết bạn - FR-070, FR-072"])
        S2(["Chấp nhận, từ chối - FR-071"])
        S3(["Xem bạn bè, hủy kết bạn - FR-073, FR-074"])
        S4(["Mở DM và nhắn - FR-075, FR-076"])
    end
    subgraph Others["Presence và Notification"]
        P1(["Xem online, offline của bạn bè và người chung server - FR-080, FR-081, FR-083"])
        N1(["Xem unread, mention - FR-090, FR-091, FR-093"])
        N2(["Đánh dấu đã đọc - FR-092"])
    end
    Member --> M1
    Member --> M2
    Member --> M3
    Member --> M4
    Member --> M5
    Member --> M6
    M6 -.-> Cld
    User --> S1
    User --> S2
    User --> S3
    User --> S4
    User --> P1
    User --> N1
    User --> N2
```
