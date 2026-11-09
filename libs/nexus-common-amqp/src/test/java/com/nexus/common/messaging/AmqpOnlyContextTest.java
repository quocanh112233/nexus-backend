package com.nexus.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

// Kiểm tra lib amqp chạy được trong service không có database
@SpringBootTest(
    classes = AmqpOnlyContextTest.App.class,
    properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class AmqpOnlyContextTest {

  // Ứng dụng tối thiểu không có DataSource hay JPA
  @SpringBootApplication
  static class App {}

  @Autowired private ApplicationContext ctx;

  // Kịch bản: context khởi động không cần database
  // Input: classpath chỉ có starter amqp, listener auto-startup tắt
  // Output: có EventEnvelopeReader và ContainerCustomizer retry, không có DataSource
  @Test
  void contextStartsWithoutDatabase() {
    assertThat(ctx.getBeansOfType(EventEnvelopeReader.class)).hasSize(1);
    assertThat(ctx.getBeansOfType(ContainerCustomizer.class))
        .containsKey("retryContainerCustomizer");
    assertThat(ctx.getBeansOfType(DataSource.class)).isEmpty();
    assertThat(ctx.getBeansOfType(SimpleMessageListenerContainer.class)).isEmpty();
  }
}
