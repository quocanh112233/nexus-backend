package com.nexus.common.messaging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

// Dựng topology chuẩn: exchange topic của producer, queue quorum của consumer cùng DLX và DLQ
public final class EventTopology {

  public static final String DLX = "nexus.dlx";

  // Lớp tiện ích, không khởi tạo
  private EventTopology() {}

  // Exchange topic bền cho event của một producer
  public static Declarables exchange(String name) {
    return new Declarables(new TopicExchange(name, true, false));
  }

  // Queue quorum bind từng routing key vào exchange, lỗi thì dead-letter sang <queue>.dlq
  public static Declarables consumerQueue(String queue, String exchange, String... routingKeys) {
    String dlqName = queue + ".dlq";
    Queue main =
        QueueBuilder.durable(queue)
            .quorum()
            .deadLetterExchange(DLX)
            .deadLetterRoutingKey(dlqName)
            .build();
    Queue dlq = QueueBuilder.durable(dlqName).quorum().build();
    List<Declarable> all = new ArrayList<>();
    all.add(main);
    all.add(dlq);
    all.add(new DirectExchange(DLX, true, false));
    all.add(new Binding(dlqName, DestinationType.QUEUE, DLX, dlqName, null));
    for (String key : routingKeys) {
      all.add(new Binding(queue, DestinationType.QUEUE, exchange, key, null));
    }
    return new Declarables(all);
  }
}
