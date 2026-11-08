package com.nexus.messaging.interfaces.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.messaging.application.channel.CommunityEvent;
import com.nexus.testsupport.ContractFixtures;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Kiểm tra CommunityEvent.from đọc được fixture của các event community mà messaging consume
class CommunityEventContractTest {

  // Kịch bản: fixture của event messaging consume được CommunityEvent.from đọc đúng serverId
  // Input: payload fixture theo eventType bọc trong EventEnvelope
  // Output: from thành công, eventType và serverId khớp fixture
  @ParameterizedTest
  @ValueSource(
      strings = {
        "ChannelCreated",
        "ChannelDeleted",
        "ChannelPermissionsChanged",
        "ServerDeleted",
        "MemberLeft",
        "MemberKicked",
        "MemberBanned",
        "MemberRolesChanged",
        "RoleUpdated",
        "RoleDeleted"
      })
  void fixtureParsesIntoCommunityEvent(String eventType) {
    Map<String, Object> payload = ContractFixtures.payload(eventType);

    CommunityEvent event = CommunityEvent.from(envelope(eventType, payload));

    assertThat(event.eventType()).isEqualTo(eventType);
    assertThat(event.serverId()).isEqualTo(UUID.fromString((String) payload.get("serverId")));
  }

  // Kịch bản: event kênh tạo hoặc xóa đọc đúng channelId từ fixture
  // Input: fixture ChannelCreated hoặc ChannelDeleted bọc trong EventEnvelope
  // Output: CommunityEvent.channelId khớp channelId của fixture
  @ParameterizedTest
  @ValueSource(strings = {"ChannelCreated", "ChannelDeleted"})
  void channelEventsCarryChannelId(String eventType) {
    Map<String, Object> payload = ContractFixtures.payload(eventType);

    CommunityEvent event = CommunityEvent.from(envelope(eventType, payload));

    assertThat(event.channelId()).isEqualTo(UUID.fromString((String) payload.get("channelId")));
  }

  // Kịch bản: event kênh tạo hoặc xóa thiếu channelId bị từ chối
  // Input: fixture ChannelCreated hoặc ChannelDeleted đã bỏ channelId
  // Output: IllegalArgumentException
  @ParameterizedTest
  @ValueSource(strings = {"ChannelCreated", "ChannelDeleted"})
  void channelEventsRequireChannelId(String eventType) {
    Map<String, Object> payload = new HashMap<>(ContractFixtures.payload(eventType));
    payload.remove("channelId");

    assertThatThrownBy(() -> CommunityEvent.from(envelope(eventType, payload)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Bọc payload vào envelope community.events
  private static EventEnvelope envelope(String eventType, Map<String, Object> payload) {
    return new EventEnvelope(
        UUID.randomUUID(),
        eventType,
        1,
        Instant.now(),
        "community-service",
        "Server",
        UUID.randomUUID(),
        payload);
  }
}
