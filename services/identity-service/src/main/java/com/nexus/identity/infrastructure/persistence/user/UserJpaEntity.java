package com.nexus.identity.infrastructure.persistence.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

// Ánh xạ bảng users; version null nghĩa là bản ghi mới nên save sẽ persist
@Entity
@Table(name = "users")
class UserJpaEntity {

  @Id private UUID id;

  @Column(nullable = false)
  private String email;

  @Column(nullable = false)
  private String username;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Column(name = "about_me")
  private String aboutMe;

  @Column(name = "avatar_public_id")
  private String avatarPublicId;

  @Column(name = "avatar_url")
  private String avatarUrl;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private Long version;

  // Constructor mặc định cho JPA
  protected UserJpaEntity() {}

  // Id người dùng
  UUID getId() {
    return id;
  }

  // Gán id
  void setId(UUID id) {
    this.id = id;
  }

  // Email đã chuẩn hóa
  String getEmail() {
    return email;
  }

  // Gán email
  void setEmail(String email) {
    this.email = email;
  }

  // Tên đăng nhập đã chuẩn hóa
  String getUsername() {
    return username;
  }

  // Gán tên đăng nhập
  void setUsername(String username) {
    this.username = username;
  }

  // Chuỗi băm mật khẩu
  String getPasswordHash() {
    return passwordHash;
  }

  // Gán chuỗi băm mật khẩu
  void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  // Tên hiển thị
  String getDisplayName() {
    return displayName;
  }

  // Gán tên hiển thị
  void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  // Giới thiệu bản thân
  String getAboutMe() {
    return aboutMe;
  }

  // Gán giới thiệu bản thân
  void setAboutMe(String aboutMe) {
    this.aboutMe = aboutMe;
  }

  // Public id của avatar
  String getAvatarPublicId() {
    return avatarPublicId;
  }

  // Gán public id của avatar
  void setAvatarPublicId(String avatarPublicId) {
    this.avatarPublicId = avatarPublicId;
  }

  // Url của avatar
  String getAvatarUrl() {
    return avatarUrl;
  }

  // Gán url của avatar
  void setAvatarUrl(String avatarUrl) {
    this.avatarUrl = avatarUrl;
  }

  // Thời điểm tạo
  Instant getCreatedAt() {
    return createdAt;
  }

  // Gán thời điểm tạo
  void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  // Thời điểm cập nhật gần nhất
  Instant getUpdatedAt() {
    return updatedAt;
  }

  // Gán thời điểm cập nhật
  void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  // Phiên bản khóa lạc quan
  Long getVersion() {
    return version;
  }

  // Gán phiên bản khóa lạc quan
  void setVersion(Long version) {
    this.version = version;
  }
}
