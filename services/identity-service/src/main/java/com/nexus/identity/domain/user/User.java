package com.nexus.identity.domain.user;

import com.nexus.identity.domain.shared.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Aggregate người dùng, đổi trạng thái qua method nghiệp vụ và phát sự kiện miền
public final class User {

  private final UserId id;
  private final Email email;
  private final Username username;
  private final PasswordHash passwordHash;
  private final Instant createdAt;
  private final Long version;
  private DisplayName displayName;
  private AboutMe aboutMe;
  private MediaRef avatar;
  private Instant updatedAt;
  private final List<DomainEvent> events = new ArrayList<>();

  // Khởi tạo nội bộ, chỉ gọi qua register hoặc rehydrate
  private User(
      UserId id,
      Email email,
      Username username,
      DisplayName displayName,
      AboutMe aboutMe,
      PasswordHash passwordHash,
      MediaRef avatar,
      Instant createdAt,
      Instant updatedAt,
      Long version) {
    this.id = id;
    this.email = email;
    this.username = username;
    this.displayName = displayName;
    this.aboutMe = aboutMe;
    this.passwordHash = passwordHash;
    this.avatar = avatar;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.version = version;
  }

  // Đăng ký user mới chưa lưu và phát UserRegistered
  public static User register(
      UserId id,
      Email email,
      Username username,
      DisplayName displayName,
      PasswordHash hash,
      Instant now) {
    User user =
        new User(id, email, username, displayName, new AboutMe(null), hash, null, now, now, null);
    user.events.add(new UserRegistered(id, username, displayName));
    return user;
  }

  // Dựng lại user từ lưu trữ, không phát sự kiện
  public static User rehydrate(
      UserId id,
      Email email,
      Username username,
      DisplayName displayName,
      AboutMe aboutMe,
      PasswordHash hash,
      MediaRef avatar,
      Instant createdAt,
      Instant updatedAt,
      Long version) {
    return new User(
        id, email, username, displayName, aboutMe, hash, avatar, createdAt, updatedAt, version);
  }

  // Cập nhật tên hiển thị và giới thiệu rồi phát UserProfileUpdated
  public void updateProfile(DisplayName displayName, AboutMe aboutMe, Instant now) {
    this.displayName = displayName;
    this.aboutMe = aboutMe;
    this.updatedAt = now;
    emitProfileUpdated();
  }

  // Đổi avatar rồi phát UserProfileUpdated
  public void changeAvatar(MediaRef avatar, Instant now) {
    this.avatar = avatar;
    this.updatedAt = now;
    emitProfileUpdated();
  }

  // Trả các sự kiện đã phát rồi xóa hàng đợi
  public List<DomainEvent> pullEvents() {
    List<DomainEvent> pulled = List.copyOf(events);
    events.clear();
    return pulled;
  }

  // Phát UserProfileUpdated với trạng thái hiện tại
  private void emitProfileUpdated() {
    events.add(
        new UserProfileUpdated(id, displayName, avatar == null ? null : avatar.url(), aboutMe));
  }

  // Id người dùng
  public UserId id() {
    return id;
  }

  // Email đăng nhập
  public Email email() {
    return email;
  }

  // Tên đăng nhập
  public Username username() {
    return username;
  }

  // Tên hiển thị
  public DisplayName displayName() {
    return displayName;
  }

  // Giới thiệu bản thân
  public AboutMe aboutMe() {
    return aboutMe;
  }

  // Chuỗi băm mật khẩu
  public PasswordHash passwordHash() {
    return passwordHash;
  }

  // Avatar, null nếu chưa đặt
  public MediaRef avatar() {
    return avatar;
  }

  // Thời điểm tạo
  public Instant createdAt() {
    return createdAt;
  }

  // Thời điểm cập nhật gần nhất
  public Instant updatedAt() {
    return updatedAt;
  }

  // Phiên bản khóa lạc quan, null khi chưa lưu
  public Long version() {
    return version;
  }
}
