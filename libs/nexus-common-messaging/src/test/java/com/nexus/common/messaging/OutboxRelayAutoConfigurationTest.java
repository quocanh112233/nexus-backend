package com.nexus.common.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

// Kiểm tra OutboxRelayAutoConfiguration từ chối cấu hình thiếu publisher confirm
class OutboxRelayAutoConfigurationTest {

  // Kịch bản: tạo bean relay khi publisher-confirm-type chưa bật
  // Input: RabbitTemplate với connection factory không bật confirm
  // Output: ném IllegalStateException nêu publisher-confirm-type=correlated
  @Test
  void failsFastWhenPublisherConfirmsOff() {
    var rabbit = new RabbitTemplate(new CachingConnectionFactory("localhost"));

    assertThatThrownBy(
            () ->
                new OutboxRelayAutoConfiguration()
                    .outboxRelay(null, rabbit, null, null, null, null, "x"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("publisher-confirm-type=correlated");
  }
}
