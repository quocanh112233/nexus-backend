package com.nexus.consumerapp.probe;

import com.nexus.common.messaging.EventTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Topology test it.src -> it.events chỉ bật khi it.probe.enabled
@Configuration
@ConditionalOnProperty("it.probe.enabled")
public class ProbeTopology {

  // Exchange nguồn it.src
  @Bean
  Declarables probeExchange() {
    return EventTopology.exchange("it.src");
  }

  // Queue it.events bind mọi routing key cùng DLQ
  @Bean
  Declarables probeQueue() {
    return EventTopology.consumerQueue("it.events", "it.src", "#");
  }
}
