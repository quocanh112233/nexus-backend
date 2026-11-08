package com.nexus.common.messaging;

import java.time.Clock;
import java.time.Duration;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

// Cấu hình phía consumer: đọc envelope, inbox và retry 3 lần rồi dead-letter
@AutoConfiguration(
    after = OutboxAutoConfiguration.class,
    afterName = {
      "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
    })
@ConditionalOnClass(RabbitTemplate.class)
public class EventConsumerAutoConfiguration {

  // Bean đọc envelope từ message
  @Bean
  @ConditionalOnMissingBean
  EventEnvelopeReader eventEnvelopeReader(JsonMapper mapper) {
    return new EventEnvelopeReader(mapper);
  }

  // Bean inbox, chỉ khi service có JdbcTemplate; schema lấy từ hibernate.default_schema
  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean
  InboxGuard inboxGuard(JdbcTemplate jdbc, Clock clock, Environment env) {
    return new InboxGuard(
        jdbc, clock, env.getProperty("spring.jpa.properties.hibernate.default_schema"));
  }

  // Retry 3 lần (1 s, x2), lỗi không retry được hoặc hết retry thì vào DLQ, shutdown chờ 30 s
  @Bean
  @ConditionalOnMissingBean
  ContainerCustomizer<SimpleMessageListenerContainer> retryContainerCustomizer() {
    return container -> {
      container.setShutdownTimeout(30_000);
      container.setAdviceChain(
          RetryInterceptorBuilder.stateless()
              .configureRetryPolicy(
                  p ->
                      p.maxRetries(2)
                          .delay(Duration.ofSeconds(1))
                          .multiplier(2)
                          .maxDelay(Duration.ofSeconds(4))
                          .excludes(
                              AmqpRejectAndDontRequeueException.class,
                              MessageConversionException.class,
                              IllegalArgumentException.class))
              .recoverer(new RejectAndDontRequeueRecoverer())
              .build());
    };
  }
}
