# ADR-008: Không dùng Lombok

Ghi lại Lombok là gì và vì sao đề xuất không dùng nó trong Nexus.

- **Trạng thái:** Accepted — người dùng duyệt (P-33)

## Bối cảnh: Lombok là gì?

Lombok là một thư viện Java **tự sinh code lặp đi lặp lại lúc compile**. Thay vì tự viết getter, setter, constructor, `equals/hashCode`, `toString`, ta gắn annotation và Lombok "chèn" code đó vào file `.class`:

```java
// Role trong server, getter/setter/constructor do Lombok sinh lúc compile
@Getter @Setter @AllArgsConstructor
public class Role {
    private UUID id;
    private String name;
}
```

Đoạn trên tương đương tự viết `getId()`, `getName()`, `setId(...)`, `setName(...)` và constructor 2 tham số.

Ưu điểm: ít gõ. Nhược điểm: code thật không nhìn thấy trong file nguồn; IDE cần plugin; dễ vô tình sinh **setter công khai** cho mọi field; người mới khó hiểu lỗi khi annotation tương tác với JPA/Jackson.

Từ Java 16 có `record`: kiểu dữ liệu bất biến, tự có constructor, accessor, `equals/hashCode/toString` — không cần thư viện ngoài:

```java
// Địa chỉ email đã được kiểm tra hợp lệ ngay khi tạo
public record Email(String value) {
    public Email {
        if (value == null || !value.contains("@")) throw new IllegalArgumentException("email không hợp lệ");
    }
}
```

## Quyết định

- **Không dùng Lombok** trong toàn bộ backend.
- **`record`** cho: value object (`Email`, `ChannelName`, `PermissionSet`, `MediaRef`), DTO REST, DTO consumer event, payload event.
- **Entity/aggregate** (`User`, `Message`, `FriendRequest`...): viết constructor và getter tường minh; **không có setter công khai** — trạng thái chỉ đổi qua method nghiệp vụ (`message.edit(...)`, `request.accept(...)`).
- JPA entity ở `infrastructure/persistence` (tách khỏi domain) cũng viết tay; IDE sinh getter/constructor khi cần.

## Hệ quả

- (+) Mọi code nhìn thấy được, dễ học, dễ debug; không cần plugin IDE.
- (+) Ép thiết kế đúng DDD: aggregate bảo vệ bất biến, không lộ setter.
- (+) `record` là Java chuẩn, nhà tuyển dụng đánh giá tốt.
- (-) Entity dài hơn vài chục dòng. Chấp nhận.
- Nếu sau này muốn dùng Lombok, chỉ cho phép ở `infrastructure` (không bao giờ ở `domain`) và ghi ADR mới.
