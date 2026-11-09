package com.nexus.realtime.infrastructure.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

// Unit test RealtimeEventPublisher: callback event bị trả lại và kết quả khi gửi lỗi
class RealtimeEventPublisherTest {

  private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
  private final RealtimeEventPublisher publisher =
      new RealtimeEventPublisher(rabbit, JsonMapper.builder().build(), Clock.systemUTC());

  // Kịch bản: event chưa có queue nhận không được log ERROR của RabbitTemplate
  // Input: khởi tạo publisher với template giả
  // Output: template được gắn returns callback
  @Test
  void installsReturnsCallback() {
    verify(rabbit).setReturnsCallback(any());
  }

  // Kịch bản: broker lỗi khi gửi
  // Input: template ném AmqpIOException, gọi publishSessionDisconnected
  // Output: trả false và không ném ngoại lệ
  @Test
  void publishFailureReturnsFalse() {
    doThrow(new AmqpIOException(new java.io.IOException("x")))
        .when(rabbit)
        .send(anyString(), anyString(), any(Message.class));

    assertThat(publisher.publishSessionDisconnected("s", UUID.randomUUID(), "i")).isFalse();
  }
}
