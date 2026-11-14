package com.nexus.notification.interfaces.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.notification.application.community.CommunityEvent;
import com.nexus.testsupport.ContractFixtures;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Kiểm tra CommunityEvent.from đọc được fixture của các event community mà notification consume
class CommunityEventContractTest {

  private static final String[] TYPES = {
    "ChannelCreated",
    "ChannelDeleted",
    "MemberJoined",
    "MemberLeft",
    "MemberKicked",
    "MemberBanned",
    "ServerDeleted"
  };

  // Kịch bản: fixture của từng loại hỗ trợ được from đọc đúng id
  // Input: payload fixture theo eventType bọc trong EventEnvelope
  // Output: eventType, serverId khớp; channelId, userId, joinedAt khớp khi fixture có
  @ParameterizedTest
  @ValueSource(
      strings = {
        "ChannelCreated",
        "ChannelDeleted",
        "MemberJoined",
        "MemberLeft",
        "MemberKicked",
        "MemberBanned",
        "ServerDeleted"
      })
  void fixtureParsesIntoCommunityEvent(String type) {
    Map<String, Object> payload = ContractFixtures.payload(type);

    CommunityEvent event = CommunityEvent.from(envelope(type, payload));

    assertThat(event.eventType()).isEqualTo(type);
    assertThat(event.serverId()).isEqualTo(id(payload, "serverId"));
    assertThat(event.channelId()).isEqualTo(id(payload, "channelId"));
    assertThat(event.userId()).isEqualTo(id(payload, "userId"));
    if (type.equals("MemberJoined")) {
      assertThat(event.joinedAt()).isEqualTo(Instant.parse((String) payload.get("joinedAt")));
    } else {
      assertThat(event.joinedAt()).isNull();
    }
  }

  // Kịch bản: loại hỗ trợ thiếu serverId bị từ chối
  // Input: fixture đã bỏ serverId theo từng eventType được hỗ trợ
  // Output: IllegalArgumentException
  @Test
  void supportedTypeRequiresServerId() {
    for (String type : TYPES) {
      assertRejected(type, "serverId");
    }
  }

  // Kịch bản: event kênh tạo hoặc xóa thiếu channelId bị từ chối
  // Input: fixture ChannelCreated hoặc ChannelDeleted đã bỏ channelId
  // Output: IllegalArgumentException
  @ParameterizedTest
  @ValueSource(strings = {"ChannelCreated", "ChannelDeleted"})
  void channelEventsRequireChannelId(String type) {
    assertRejected(type, "channelId");
  }

  // Kịch bản: event thành viên thiếu userId bị từ chối
  // Input: fixture Member* đã bỏ userId
  // Output: IllegalArgumentException
  @ParameterizedTest
  @ValueSource(strings = {"MemberJoined", "MemberLeft", "MemberKicked", "MemberBanned"})
  void memberEventsRequireUserId(String type) {
    assertRejected(type, "userId");
  }

  // Kịch bản: MemberJoined thiếu hoặc sai joinedAt bị từ chối
  // Input: fixture MemberJoined bỏ joinedAt, rồi joinedAt không phải thời điểm ISO
  // Output: IllegalArgumentException cho cả hai
  @Test
  void memberJoinedRequiresValidJoinedAt() {
    assertRejected("MemberJoined", "joinedAt");
    Map<String, Object> payload = new HashMap<>(ContractFixtures.payload("MemberJoined"));
    payload.put("joinedAt", "không-phải-thời-điểm");

    assertThatThrownBy(() -> CommunityEvent.from(envelope("MemberJoined", payload)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Kịch bản: loại lạ không có payload vẫn được đọc để bỏ qua
  // Input: eventType Whatever với payload rỗng
  // Output: from không ném lỗi, mọi id và joinedAt là null
  @Test
  void unknownTypeIsNotRejected() {
    CommunityEvent event = CommunityEvent.from(envelope("Whatever", Map.of()));

    assertThat(event.eventType()).isEqualTo("Whatever");
    assertThat(event.serverId()).isNull();
    assertThat(event.channelId()).isNull();
    assertThat(event.userId()).isNull();
    assertThat(event.joinedAt()).isNull();
  }

  // Bỏ field khỏi fixture của type rồi khẳng định from bị từ chối
  private static void assertRejected(String type, String field) {
    Map<String, Object> payload = new HashMap<>(ContractFixtures.payload(type));
    payload.remove(field);

    assertThatThrownBy(() -> CommunityEvent.from(envelope(type, payload)))
        .as(type + " thiếu " + field)
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Đọc UUID của field trong payload, null nếu fixture không có
  private static UUID id(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    return value == null ? null : UUID.fromString(value.toString());
  }

  // Bọc payload vào envelope community.events
  private static EventEnvelope envelope(String type, Map<String, Object> payload) {
    return new EventEnvelope(
        UUID.randomUUID(),
        type,
        1,
        Instant.now(),
        "community-service",
        "Server",
        UUID.randomUUID(),
        payload);
  }
}
