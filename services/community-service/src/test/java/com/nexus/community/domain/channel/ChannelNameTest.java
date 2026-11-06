package com.nexus.community.domain.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.domain.shared.DomainValidationException;
import org.junit.jupiter.api.Test;

// Kiểm thử định dạng của ChannelName
class ChannelNameTest {

  // Kịch bản: tên đúng định dạng được giữ nguyên
  // Input: "general" và "dev-chat-2"
  // Output: giá trị không đổi
  @Test
  void acceptsValid() {
    assertThat(new ChannelName("general").value()).isEqualTo("general");
    assertThat(new ChannelName("dev-chat-2").value()).isEqualTo("dev-chat-2");
  }

  // Kịch bản: tên sai định dạng hoặc null bị từ chối, không tự hạ chữ hay cắt khoảng trắng
  // Input: "Chat", "a b", "", 101 ký tự, "ký-tự" và null
  // Output: tất cả ném DomainValidationException
  @Test
  void rejectsInvalid() {
    for (String bad : new String[] {"Chat", "a b", "", "a".repeat(101), "ký-tự", null}) {
      assertThatThrownBy(() -> new ChannelName(bad)).isInstanceOf(DomainValidationException.class);
    }
  }
}
