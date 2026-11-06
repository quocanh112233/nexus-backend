package com.nexus.community.domain.server;

import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Aggregate server, giữ danh sách role và phát sự kiện miền
public final class Server {

  private final ServerId id;
  private final UserId ownerId;
  private final List<Role> roles;
  private final Instant createdAt;
  private final long version;
  private ServerName name;
  private Instant updatedAt;
  private final List<DomainEvent> events = new ArrayList<>();

  // Khởi tạo nội bộ, chỉ gọi qua create hoặc rehydrate
  private Server(
      ServerId id,
      ServerName name,
      UserId ownerId,
      List<Role> roles,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    this.id = id;
    this.name = name;
    this.ownerId = ownerId;
    this.roles = new ArrayList<>(roles);
    this.roles.sort(Comparator.comparingInt(Role::position));
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.version = version;
  }

  // Tạo server mới kèm role @everyone và phát ServerCreated
  public static Server create(ServerName name, UserId ownerId, Instant now) {
    Role everyone =
        new Role(RoleId.newId(), "@everyone", 0, PermissionSet.EVERYONE_DEFAULT, 0, true);
    Server server = new Server(ServerId.newId(), name, ownerId, List.of(everyone), now, now, 0);
    server.events.add(new ServerCreated(server.id, name, ownerId));
    return server;
  }

  // Dựng lại server từ lưu trữ, không phát sự kiện
  public static Server rehydrate(
      ServerId id,
      ServerName name,
      UserId ownerId,
      List<Role> roles,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    return new Server(id, name, ownerId, roles, createdAt, updatedAt, version);
  }

  // Đổi tên server rồi phát ServerUpdated
  public void rename(ServerName name, Instant now) {
    this.name = name;
    this.updatedAt = now;
    events.add(new ServerUpdated(id, name));
  }

  // Đánh dấu xóa server và phát ServerDeleted
  public void delete() {
    events.add(new ServerDeleted(id));
  }

  // Tạo role ở vị trí cho trước (null là 1), đẩy role phía trên lên và phát RoleCreated
  public Role createRole(
      String name, int color, PermissionSet permissions, Integer position, Instant now) {
    String trimmed = validName(name);
    validColor(color);
    if (permissions == null) {
      throw new DomainValidationException("permissions is required");
    }
    List<Role> ordered = nonDefaultRoles();
    int target = position == null ? 1 : position;
    if (target < 1 || target > ordered.size() + 1) {
      throw new DomainValidationException("position out of range");
    }
    Role role = new Role(RoleId.newId(), trimmed, color, permissions, target, false);
    ordered.add(target - 1, role);
    layout(ordered);
    updatedAt = now;
    Role placed = findRole(role.id()).orElseThrow();
    events.add(new RoleCreated(id, placed.id(), placed.name(), placed.permissions(), target));
    return placed;
  }

  // Cập nhật role (@everyone cố định tên và vị trí), phát tối đa một RoleUpdated
  public void updateRole(RoleId roleId, RoleChanges changes, Instant now) {
    Role current =
        findRole(roleId).orElseThrow(() -> new DomainValidationException("role not found"));
    if (current.isDefault() && (changes.name() != null || changes.position() != null)) {
      throw new DomainValidationException("default role name and position are fixed");
    }
    String name = changes.name() == null ? current.name() : validName(changes.name());
    int color = changes.color() == null ? current.color() : changes.color();
    validColor(color);
    PermissionSet permissions =
        changes.permissions() == null ? current.permissions() : changes.permissions();
    List<Role> ordered = nonDefaultRoles();
    int position = current.position();
    if (changes.position() != null) {
      if (changes.position() < 1 || changes.position() > ordered.size()) {
        throw new DomainValidationException("position out of range");
      }
      position = changes.position();
    }
    Role next = new Role(roleId, name, color, permissions, position, current.isDefault());
    if (next.equals(current)) {
      return;
    }
    if (current.isDefault()) {
      roles.replaceAll(r -> r.id().equals(roleId) ? next : r);
    } else {
      ordered.removeIf(r -> r.id().equals(roleId));
      ordered.add(position - 1, next);
      layout(ordered);
    }
    updatedAt = now;
    events.add(new RoleUpdated(id, roleId, name, permissions, position));
  }

  // Gỡ role theo id, từ chối role mặc định hoặc không tồn tại, đánh số lại dày và phát RoleDeleted
  public void removeRole(RoleId roleId, Instant now) {
    Role role = findRole(roleId).orElseThrow(() -> new DomainValidationException("role not found"));
    if (role.isDefault()) {
      throw new DomainValidationException("default role cannot be removed");
    }
    List<Role> ordered = nonDefaultRoles();
    ordered.removeIf(r -> r.id().equals(roleId));
    layout(ordered);
    updatedAt = now;
    events.add(new RoleDeleted(id, roleId));
  }

  // Tìm role theo id
  public Optional<Role> findRole(RoleId roleId) {
    return roles.stream().filter(r -> r.id().equals(roleId)).findFirst();
  }

  // Role @everyone mặc định
  public Role defaultRole() {
    return roles.stream().filter(Role::isDefault).findFirst().orElseThrow();
  }

  // Các role không mặc định theo vị trí tăng dần, danh sách sửa được
  private List<Role> nonDefaultRoles() {
    return new ArrayList<>(roles.stream().filter(r -> !r.isDefault()).toList());
  }

  // Dựng lại danh sách role: @everyone ở 0 rồi các role còn lại đánh số dày từ 1
  private void layout(List<Role> ordered) {
    Role everyone = defaultRole();
    roles.clear();
    roles.add(everyone);
    for (int i = 0; i < ordered.size(); i++) {
      Role r = ordered.get(i);
      roles.add(new Role(r.id(), r.name(), r.color(), r.permissions(), i + 1, false));
    }
  }

  // Cắt khoảng trắng và kiểm tra tên role dài 1..100
  private static String validName(String name) {
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.isEmpty() || trimmed.length() > 100) {
      throw new DomainValidationException("role name must be 1..100 characters");
    }
    return trimmed;
  }

  // Kiểm tra màu nằm trong 0..16777215
  private static void validColor(int color) {
    if (color < 0 || color > 0xFFFFFF) {
      throw new DomainValidationException("role color out of range");
    }
  }

  // Cho biết user có phải chủ server không
  public boolean isOwner(UserId userId) {
    return ownerId.equals(userId);
  }

  // Trả các sự kiện đã phát rồi xóa hàng đợi
  public List<DomainEvent> pullEvents() {
    List<DomainEvent> pulled = List.copyOf(events);
    events.clear();
    return pulled;
  }

  // Id server
  public ServerId id() {
    return id;
  }

  // Tên server
  public ServerName name() {
    return name;
  }

  // Id chủ server
  public UserId ownerId() {
    return ownerId;
  }

  // Danh sách role chỉ đọc
  public List<Role> roles() {
    return Collections.unmodifiableList(roles);
  }

  // Thời điểm tạo
  public Instant createdAt() {
    return createdAt;
  }

  // Thời điểm cập nhật gần nhất
  public Instant updatedAt() {
    return updatedAt;
  }

  // Phiên bản khóa lạc quan
  public long version() {
    return version;
  }
}
