package com.nexus.community.domain.server;

import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

  // Gỡ role theo id, từ chối role mặc định hoặc role không tồn tại
  public void removeRole(RoleId roleId) {
    Role role =
        roles.stream()
            .filter(r -> r.id().equals(roleId))
            .findFirst()
            .orElseThrow(() -> new DomainValidationException("role not found"));
    if (role.isDefault()) {
      throw new DomainValidationException("default role cannot be removed");
    }
    roles.remove(role);
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
