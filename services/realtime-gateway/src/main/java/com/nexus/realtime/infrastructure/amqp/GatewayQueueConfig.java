package com.nexus.realtime.infrastructure.amqp;

import com.nexus.common.messaging.EventTopology;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Khai báo exchange của các producer, queue riêng của instance và binding vào các exchange đó
@Configuration
public class GatewayQueueConfig {

  // Định danh instance, cấu hình rỗng thì sinh UUID lúc khởi động
  @Bean
  InstanceId instanceId(@Value("${app.instance-id:}") String configured) {
    return new InstanceId(configured.isBlank() ? UUID.randomUUID().toString() : configured);
  }

  // Queue realtime-gateway.<instanceId> không bền, exclusive, tự xóa
  @Bean
  Queue instanceQueue(InstanceId instanceId) {
    return new Queue("realtime-gateway." + instanceId.value(), false, true, true);
  }

  // Exchange phải có trước khi bind, binding theo bảng event của realtime-design
  @Bean
  Declarables gatewayTopology(Queue instanceQueue) {
    List<Declarable> all = new ArrayList<>();
    for (String exchange :
        List.of("community.events", "messaging.events", "presence.events", "realtime.events")) {
      all.addAll(EventTopology.exchange(exchange).getDeclarables());
    }
    all.add(bind(instanceQueue, "community.events", "#"));
    all.add(bind(instanceQueue, "messaging.events", "#"));
    all.add(bind(instanceQueue, "presence.events", "presence.changed"));
    all.add(bind(instanceQueue, "realtime.events", "typing.started"));
    return new Declarables(all);
  }

  // Binding queue vào exchange theo routing key
  private static Binding bind(Queue queue, String exchange, String key) {
    return new Binding(queue.getName(), Binding.DestinationType.QUEUE, exchange, key, null);
  }
}
