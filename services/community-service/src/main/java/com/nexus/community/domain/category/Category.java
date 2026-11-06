package com.nexus.community.domain.category;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.Position;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Aggregate category gom các kênh trong server và phát sự kiện miền
public final class Category {

  private final CategoryId id;
  private final ServerId serverId;
  private final Instant createdAt;
  private CategoryName name;
  private Position position;
  private Instant updatedAt;
  private final List<DomainEvent> events = new ArrayList<>();

  // Khởi tạo nội bộ, chỉ gọi qua create hoặc rehydrate
  private Category(
      CategoryId id,
      ServerId serverId,
      CategoryName name,
      Position position,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.serverId = serverId;
    this.name = name;
    this.position = position;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  // Tạo category mới và phát CategoryCreated
  public static Category create(
      ServerId serverId, CategoryName name, Position position, Instant now) {
    Category category = new Category(CategoryId.newId(), serverId, name, position, now, now);
    category.events.add(new CategoryCreated(serverId, category.id, name, position));
    return category;
  }

  // Dựng lại category từ lưu trữ, không phát sự kiện
  public static Category rehydrate(
      CategoryId id,
      ServerId serverId,
      CategoryName name,
      Position position,
      Instant createdAt,
      Instant updatedAt) {
    return new Category(id, serverId, name, position, createdAt, updatedAt);
  }

  // Đổi tên category rồi phát CategoryUpdated
  public void rename(CategoryName name, Instant now) {
    this.name = name;
    this.updatedAt = now;
    emitUpdated();
  }

  // Đổi vị trí category rồi phát CategoryUpdated
  public void moveTo(Position position, Instant now) {
    this.position = position;
    this.updatedAt = now;
    emitUpdated();
  }

  // Đánh dấu xóa category và phát CategoryDeleted
  public void delete() {
    events.add(new CategoryDeleted(serverId, id));
  }

  // Trả các sự kiện đã phát rồi xóa hàng đợi
  public List<DomainEvent> pullEvents() {
    List<DomainEvent> pulled = List.copyOf(events);
    events.clear();
    return pulled;
  }

  // Phát CategoryUpdated với trạng thái hiện tại
  private void emitUpdated() {
    events.add(new CategoryUpdated(serverId, id, name, position));
  }

  // Id category
  public CategoryId id() {
    return id;
  }

  // Id server chứa category
  public ServerId serverId() {
    return serverId;
  }

  // Tên category
  public CategoryName name() {
    return name;
  }

  // Vị trí sắp xếp
  public Position position() {
    return position;
  }

  // Thời điểm tạo
  public Instant createdAt() {
    return createdAt;
  }

  // Thời điểm cập nhật gần nhất
  public Instant updatedAt() {
    return updatedAt;
  }
}
