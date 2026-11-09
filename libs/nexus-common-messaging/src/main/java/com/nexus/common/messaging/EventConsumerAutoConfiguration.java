package com.nexus.common.messaging;

import java.time.Clock;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

// Cấu hình phía consumer cần database: inbox chống xử lý trùng
@AutoConfiguration(
    after = {OutboxAutoConfiguration.class, AmqpConsumerAutoConfiguration.class},
    afterName = {
      "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
    })
@ConditionalOnClass(RabbitTemplate.class)
public class EventConsumerAutoConfiguration {

  // Bean inbox, chỉ khi service có JdbcTemplate; schema lấy từ hibernate.default_schema
  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean
  InboxGuard inboxGuard(JdbcTemplate jdbc, Clock clock, Environment env) {
    return new InboxGuard(
        jdbc, clock, env.getProperty("spring.jpa.properties.hibernate.default_schema"));
  }
}
