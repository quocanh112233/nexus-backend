package com.nexus.common.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

// Bật relay outbox khi nexus.messaging.relay.enabled=true: exchange, OutboxRelay và lịch chạy
@AutoConfiguration(
    after = OutboxAutoConfiguration.class,
    afterName = "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration")
@ConditionalOnProperty(prefix = "nexus.messaging.relay", name = "enabled", havingValue = "true")
@EnableScheduling
@EnableConfigurationProperties(RelayProperties.class)
public class OutboxRelayAutoConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayAutoConfiguration.class);

  // Exchange topic bền của producer
  @Bean
  Declarables relayExchange(RelayProperties props) {
    return EventTopology.exchange(props.exchange());
  }

  // Bean relay, đồng thời log debug khi message không route được
  @Bean
  OutboxRelay outboxRelay(
      OutboxEventDao dao,
      RabbitTemplate rabbit,
      JsonMapper mapper,
      RelayProperties props,
      Clock clock,
      TransactionTemplate tx,
      @Value("${spring.application.name:unknown}") String producer) {
    Assert.state(
        rabbit.getConnectionFactory().isPublisherConfirms(),
        "nexus.messaging.relay cần spring.rabbitmq.publisher-confirm-type=correlated");
    rabbit.setReturnsCallback(
        returned ->
            LOG.debug(
                "Message {} không route được: exchange={} routingKey={} replyText={}",
                returned.getMessage().getMessageProperties().getMessageId(),
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyText()));
    return new OutboxRelay(dao, rabbit, mapper, props, clock, tx, producer);
  }

  // Đăng ký gauge nexus.outbox.pending khi có MeterRegistry
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(MeterRegistry.class)
  static class MetricsConfiguration {

    // Gauge đếm dòng outbox chưa gửi, chỉ gắn khi service có MeterRegistry
    @Bean
    SmartInitializingSingleton outboxPendingGauge(
        ObjectProvider<MeterRegistry> registry, OutboxEventDao dao) {
      return () ->
          registry.ifAvailable(
              r ->
                  Gauge.builder(
                          "nexus.outbox.pending", dao, OutboxEventDao::countByPublishedAtIsNull)
                      .register(r));
    }
  }
}
