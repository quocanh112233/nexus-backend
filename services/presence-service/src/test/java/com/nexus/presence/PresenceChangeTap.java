package com.nexus.presence;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Queue tạm bind presence.events presence.changed để test đọc các PresenceChanged đã phát
public class PresenceChangeTap implements AutoCloseable {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final RabbitAdmin admin;
  private final RabbitTemplate rabbit;
  private final List<JsonNode> seen = new ArrayList<>();
  private final Queue queue = new Queue("tap-" + UUID.randomUUID(), true, false, false);

  // Khai báo queue tạm và binding
  public PresenceChangeTap(RabbitAdmin admin, RabbitTemplate rabbit) {
    this.admin = admin;
    this.rabbit = rabbit;
    admin.declareQueue(queue);
    admin.declareBinding(
        new Binding(
            queue.getName(),
            Binding.DestinationType.QUEUE,
            "presence.events",
            "presence.changed",
            null));
  }

  // Chờ envelope PresenceChanged của user với status cho trước, quá 10 giây thì ném lỗi
  public JsonNode awaitStatus(UUID userId, String status) {
    List<JsonNode> hit = new ArrayList<>();
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () -> {
              hit.addAll(of(userId, status));
              return !hit.isEmpty();
            });
    return hit.get(0);
  }

  // Status của user theo thứ tự nhận, gồm cả message đã đọc trước đó
  public List<String> statuses(UUID userId) {
    return of(userId, null).stream().map(e -> e.get("payload").get("status").asText()).toList();
  }

  // Hút message mới vào danh sách đã thấy rồi lọc theo user (và status nếu có)
  private List<JsonNode> of(UUID userId, String status) {
    Message m;
    while ((m = rabbit.receive(queue.getName(), 300)) != null) {
      seen.add(MAPPER.readTree(m.getBody()));
    }
    return seen.stream()
        .filter(e -> e.get("payload").get("userId").asText().equals(userId.toString()))
        .filter(e -> status == null || e.get("payload").get("status").asText().equals(status))
        .toList();
  }

  // Xóa queue tạm
  @Override
  public void close() {
    admin.deleteQueue(queue.getName());
  }
}
