package com.nexus.community.domain.channel;

import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.Position;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// Aggregate kênh văn bản trong server và phát sự kiện miền
public final class Channel {

  private static final int MAX_TOPIC = 1024;

  private final ChannelId id;
  private final ServerId serverId;
  private final Instant createdAt;
  private final long version;
  private CategoryId categoryId;
  private ChannelName name;
  private String topic;
  private Position position;
  private Instant updatedAt;
  private final List<DomainEvent> events = new ArrayList<>();

  // Khởi tạo nội bộ, chỉ gọi qua create hoặc rehydrate
  private Channel(
      ChannelId id,
      ServerId serverId,
      CategoryId categoryId,
      ChannelName name,
      String topic,
      Position position,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    this.id = id;
    this.serverId = serverId;
    this.categoryId = categoryId;
    this.name = name;
    this.topic = topic;
    this.position = position;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.version = version;
  }

  // Tạo kênh mới chưa có topic và phát ChannelCreated
  public static Channel create(
      ServerId serverId, CategoryId categoryId, ChannelName name, Position position, Instant now) {
    Channel channel =
        new Channel(ChannelId.newId(), serverId, categoryId, name, null, position, now, now, 0);
    channel.events.add(new ChannelCreated(serverId, channel.id, categoryId, name, position));
    return channel;
  }

  // Dựng lại kênh từ lưu trữ, không phát sự kiện
  public static Channel rehydrate(
      ChannelId id,
      ServerId serverId,
      CategoryId categoryId,
      ChannelName name,
      String topic,
      Position position,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    return new Channel(
        id, serverId, categoryId, name, topic, position, createdAt, updatedAt, version);
  }

  // Áp thay đổi một phần, phát đúng một ChannelUpdated nếu có gì đổi
  public void update(ChannelChanges changes, Instant now) {
    if (changes.topic() != null && changes.topic().length() > MAX_TOPIC) {
      throw new DomainValidationException("topic must be at most 1024 characters");
    }
    ChannelName newName = changes.name() != null ? changes.name() : name;
    String newTopic = changes.topic() != null ? changes.topic() : topic;
    Position newPosition = changes.position() != null ? changes.position() : position;
    CategoryId newCategory = changes.changeCategory() ? changes.categoryId() : categoryId;
    boolean changed =
        !newName.equals(name)
            || !Objects.equals(newTopic, topic)
            || !newPosition.equals(position)
            || !Objects.equals(newCategory, categoryId);
    if (!changed) {
      return;
    }
    this.name = newName;
    this.topic = newTopic;
    this.position = newPosition;
    this.categoryId = newCategory;
    this.updatedAt = now;
    events.add(new ChannelUpdated(serverId, id, categoryId, name, topic, position));
  }

  // Đánh dấu xóa kênh và phát ChannelDeleted
  public void delete() {
    events.add(new ChannelDeleted(serverId, id));
  }

  // Trả các sự kiện đã phát rồi xóa hàng đợi
  public List<DomainEvent> pullEvents() {
    List<DomainEvent> pulled = List.copyOf(events);
    events.clear();
    return pulled;
  }

  // Id kênh
  public ChannelId id() {
    return id;
  }

  // Id server chứa kênh
  public ServerId serverId() {
    return serverId;
  }

  // Id category, null nếu kênh không thuộc category nào
  public CategoryId categoryId() {
    return categoryId;
  }

  // Tên kênh
  public ChannelName name() {
    return name;
  }

  // Chủ đề kênh, null nếu chưa đặt
  public String topic() {
    return topic;
  }

  // Loại kênh, ở P2 luôn là TEXT
  public String type() {
    return "TEXT";
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

  // Phiên bản khóa lạc quan
  public long version() {
    return version;
  }
}
