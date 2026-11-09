package com.nexus.realtime.application.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.realtime.application.subscription.SubscriptionRule.Kind;
import com.nexus.realtime.application.subscription.SubscriptionRule.Target;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Kiểm tra SubscriptionRule.parse đổi destination STOMP thành đích đã biết
class SubscriptionRuleTest {

  private static final UUID ID = UUID.fromString("0198a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");

  // Kịch bản: destination kênh hợp lệ
  // Input: /topic/channels/{uuid}
  // Output: Target CHANNEL mang đúng id
  @Test
  void parsesChannelTopic() {
    assertThat(SubscriptionRule.parse("/topic/channels/" + ID))
        .isEqualTo(new Target(Kind.CHANNEL, ID));
  }

  // Kịch bản: destination server hợp lệ
  // Input: /topic/servers/{uuid}
  // Output: Target SERVER mang đúng id
  @Test
  void parsesServerTopic() {
    assertThat(SubscriptionRule.parse("/topic/servers/" + ID))
        .isEqualTo(new Target(Kind.SERVER, ID));
  }

  // Kịch bản: hàng đợi sự kiện riêng của user
  // Input: /user/queue/events
  // Output: Target USER_EVENTS không có id
  @Test
  void parsesUserEvents() {
    assertThat(SubscriptionRule.parse("/user/queue/events"))
        .isEqualTo(new Target(Kind.USER_EVENTS, null));
  }

  // Kịch bản: đích gửi typing của client
  // Input: /app/channels/{uuid}/typing
  // Output: Target TYPING_SEND mang id kênh
  @Test
  void parsesTypingSend() {
    assertThat(SubscriptionRule.parse("/app/channels/" + ID + "/typing"))
        .isEqualTo(new Target(Kind.TYPING_SEND, ID));
  }

  // Kịch bản: destination lạ, rỗng, sai uuid hoặc thừa đuôi bị từ chối
  // Input: các chuỗi không khớp mẫu nào
  // Output: ném IllegalArgumentException
  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "/topic/x",
        "/topic/channels/not-a-uuid",
        "/topic/channels/",
        "/topic/channels/0198a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b/extra",
        "/topic/servers/1-1-1-1-1",
        "/topic/channels/0198A1B2-C3D4-7E5F-8A9B-0C1D2E3F4A5B",
        "/topic/channels/0198a1b2c-3d4-7e5f-8a9b-0c1d2e3f4a5b",
        "/topic/servers/------------------------------------",
        "/user/queue/other",
        "/app/channels/0198a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b/other",
        "/queue/events",
        "topic/channels/0198a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"
      })
  void rejectsUnknownDestinations(String destination) {
    assertThatThrownBy(() -> SubscriptionRule.parse(destination))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Kịch bản: destination null bị từ chối
  // Input: null
  // Output: ném IllegalArgumentException
  @Test
  void rejectsNullDestination() {
    assertThatThrownBy(() -> SubscriptionRule.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
