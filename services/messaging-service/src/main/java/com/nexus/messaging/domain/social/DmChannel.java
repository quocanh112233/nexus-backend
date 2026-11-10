package com.nexus.messaging.domain.social;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Aggregate kênh nhắn tin riêng giữa hai người và phát sự kiện miền
public final class DmChannel {

  private final UUID id;
  private final UserPair pair;
  private final Instant createdAt;
  private final List<DomainEvent> events = new ArrayList<>();

  // Khởi tạo nội bộ, chỉ gọi qua open hoặc rehydrate
  private DmChannel(UUID id, UserPair pair, Instant createdAt) {
    this.id = id;
    this.pair = pair;
    this.createdAt = createdAt;
  }

  // Mở kênh DM mới và phát DmChannelCreated
  public static DmChannel open(UUID id, UserPair pair, Instant now) {
    DmChannel channel = new DmChannel(id, pair, now);
    channel.events.add(new DmChannelCreated(new ChannelId(id), pair.low(), pair.high()));
    return channel;
  }

  // Dựng lại kênh từ lưu trữ, không phát sự kiện
  public static DmChannel rehydrate(UUID id, UserPair pair, Instant createdAt) {
    return new DmChannel(id, pair, createdAt);
  }

  // Lấy và xóa các sự kiện chưa phát
  public List<DomainEvent> pullEvents() {
    List<DomainEvent> pulled = List.copyOf(events);
    events.clear();
    return pulled;
  }

  // Định danh kênh
  public UUID id() {
    return id;
  }

  // Cặp người tham gia
  public UserPair pair() {
    return pair;
  }

  // Thời điểm tạo
  public Instant createdAt() {
    return createdAt;
  }
}
