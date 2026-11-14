package com.nexus.notification.interfaces.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.notification.application.messaging.MessagingEvent;
import com.nexus.testsupport.ContractFixtures;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra MessagingEvent.from đọc được fixture MessageCreated và DmChannelCreated
class MessagingEventContractTest {

  // Kịch bản: fixture MessageCreated được from đọc đúng
  // Input: payload fixture MessageCreated (serverId null) bọc trong EventEnvelope
  // Output: id, tác giả, mention, createdAt khớp fixture; serverId null; participantIds rỗng
  @Test
  void messageCreatedFixtureParses() {
    Map<String, Object> payload = ContractFixtures.payload("MessageCreated");

    MessagingEvent event = MessagingEvent.from(envelope("MessageCreated", payload));

    assertThat(event.eventType()).isEqualTo("MessageCreated");
    assertThat(event.messageId()).isEqualTo(id(payload, "messageId"));
    assertThat(event.channelId()).isEqualTo(id(payload, "channelId"));
    assertThat(event.serverId()).isNull();
    assertThat(event.authorId()).isEqualTo(id(payload, "authorId"));
    assertThat(event.mentionUserIds())
        .containsExactly(
            UUID.fromString((String) ((List<?>) payload.get("mentionUserIds")).get(0)));
    assertThat(event.createdAt()).isEqualTo(Instant.parse((String) payload.get("createdAt")));
    assertThat(event.participantIds()).isEmpty();
  }

  // Kịch bản: MessageCreated kênh server, không có mentionUserIds
  // Input: fixture MessageCreated đặt serverId và bỏ mentionUserIds
  // Output: serverId khớp, mentionUserIds rỗng
  @Test
  void messageCreatedWithServerAndNoMentions() {
    UUID server = UUID.randomUUID();
    Map<String, Object> payload = new HashMap<>(ContractFixtures.payload("MessageCreated"));
    payload.put("serverId", server.toString());
    payload.remove("mentionUserIds");

    MessagingEvent event = MessagingEvent.from(envelope("MessageCreated", payload));

    assertThat(event.serverId()).isEqualTo(server);
    assertThat(event.mentionUserIds()).isEmpty();
  }

  // Kịch bản: fixture DmChannelCreated được from đọc đúng
  // Input: payload fixture DmChannelCreated bọc trong EventEnvelope
  // Output: channelId khớp, participantIds đúng hai id theo thứ tự fixture
  @Test
  void dmChannelCreatedFixtureParses() {
    Map<String, Object> payload = ContractFixtures.payload("DmChannelCreated");

    MessagingEvent event = MessagingEvent.from(envelope("DmChannelCreated", payload));

    assertThat(event.channelId()).isEqualTo(id(payload, "channelId"));
    assertThat(event.participantIds())
        .containsExactly(
            ((List<?>) payload.get("participantIds"))
                .stream().map(o -> UUID.fromString(o.toString())).toArray(UUID[]::new));
  }

  // Kịch bản: MessageCreated thiếu messageId bị từ chối
  // Input: fixture MessageCreated đã bỏ messageId
  // Output: IllegalArgumentException
  @Test
  void messageCreatedRequiresMessageId() {
    Map<String, Object> payload = new HashMap<>(ContractFixtures.payload("MessageCreated"));
    payload.remove("messageId");

    assertThatThrownBy(() -> MessagingEvent.from(envelope("MessageCreated", payload)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Kịch bản: DmChannelCreated chỉ có một participant bị từ chối
  // Input: fixture DmChannelCreated với participantIds một phần tử
  // Output: IllegalArgumentException
  @Test
  void dmChannelCreatedRequiresTwoParticipants() {
    Map<String, Object> payload = new HashMap<>(ContractFixtures.payload("DmChannelCreated"));
    payload.put("participantIds", List.of(UUID.randomUUID().toString()));

    assertThatThrownBy(() -> MessagingEvent.from(envelope("DmChannelCreated", payload)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Kịch bản: id sai định dạng bị từ chối
  // Input: fixture MessageCreated có authorId không phải UUID
  // Output: IllegalArgumentException
  @Test
  void malformedIdRejected() {
    Map<String, Object> payload = new HashMap<>(ContractFixtures.payload("MessageCreated"));
    payload.put("authorId", "không-phải-uuid");

    assertThatThrownBy(() -> MessagingEvent.from(envelope("MessageCreated", payload)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Đọc UUID của field trong payload
  private static UUID id(Map<String, Object> payload, String key) {
    return UUID.fromString(payload.get(key).toString());
  }

  // Bọc payload vào envelope messaging.events
  private static EventEnvelope envelope(String type, Map<String, Object> payload) {
    return new EventEnvelope(
        UUID.randomUUID(),
        type,
        1,
        Instant.now(),
        "messaging-service",
        "Message",
        UUID.randomUUID(),
        payload);
  }
}
