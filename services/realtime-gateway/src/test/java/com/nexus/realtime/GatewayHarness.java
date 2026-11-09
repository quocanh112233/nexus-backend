package com.nexus.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.realtime.application.routing.Frame;
import com.nexus.testsupport.RabbitMqContainer;
import com.nexus.testsupport.TestJwt;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Dựng nhiều instance gateway cùng JVM với broker thật, JWKS và gRPC giả, kèm client STOMP
public final class GatewayHarness implements AutoCloseable {

  public static final JsonMapper MAPPER = JsonMapper.builder().build();
  public static final FakeAccessServer ACCESS = new FakeAccessServer();
  private static final TestJwt JWT = TestJwt.create();
  private static final TestJwt.JwksServer JWKS = JWT.serveJwks();
  private static final Map<String, Object> RABBIT = new HashMap<>();

  private final List<WebSocketStompClient> stomps = new ArrayList<>();

  // Khởi động container broker nếu chưa chạy
  public GatewayHarness() {
    RabbitMqContainer.register((name, value) -> RABBIT.put(name, value.get()));
  }

  // Dựng một instance gateway cổng ngẫu nhiên với các tham số bổ sung
  public ConfigurableApplicationContext instance(String instanceId, String... extra) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "--app.instance-id=" + instanceId,
                "--server.port=0",
                "--management.server.port=0",
                "--spring.rabbitmq.virtual-host=/",
                "--app.jwt.jwks-uri=" + JWKS.uri(),
                "--app.grpc.service-token=dummy-realtime",
                "--app.grpc.community.target=" + ACCESS.target(),
                "--app.grpc.messaging.target=" + ACCESS.target()));
    RABBIT.forEach((name, value) -> args.add("--" + name + "=" + value));
    args.addAll(List.of(extra));
    return SpringApplication.run(RealtimeGatewayApplication.class, args.toArray(String[]::new));
  }

  // Kết nối client STOMP của user tới instance
  public Client connect(ConfigurableApplicationContext ctx, UUID userId) throws Exception {
    WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
    stomps.add(stomp);
    StompHeaders headers = new StompHeaders();
    headers.add("Authorization", "Bearer " + JWT.token(userId, "user"));
    StompSession session =
        stomp
            .connectAsync(
                "ws://localhost:" + ctx.getEnvironment().getProperty("local.server.port") + "/ws",
                new WebSocketHttpHeaders(),
                headers,
                new StompSessionHandlerAdapter() {})
            .get(10, TimeUnit.SECONDS);
    return new Client(ctx.getBean(SimpMessagingTemplate.class), userId, session);
  }

  // Dừng mọi client STOMP đã mở
  @Override
  public void close() {
    stomps.forEach(WebSocketStompClient::stop);
  }

  // Queue tạm không consumer bind vào realtime.events theo các routing key, gom message để test đọc
  public static final class Tap {

    public final String queue = "it." + UUID.randomUUID();
    public final List<Message> messages = new ArrayList<>();
    private final RabbitTemplate rabbit;

    // Khai báo queue tạm tự xóa và bind vào các routing key
    public Tap(ConfigurableApplicationContext ctx, String... keys) {
      rabbit = ctx.getBean(RabbitTemplate.class);
      RabbitAdmin admin = ctx.getBean(RabbitAdmin.class);
      admin.declareQueue(new Queue(queue, false, true, true));
      for (String key : keys) {
        admin.declareBinding(
            new Binding(queue, Binding.DestinationType.QUEUE, "realtime.events", key, null));
      }
    }

    // Rút hết message đang chờ vào danh sách rồi trả các envelope có eventType này
    public List<JsonNode> events(String type) {
      Message m;
      while ((m = rabbit.receive(queue)) != null) {
        messages.add(m);
      }
      return messages.stream()
          .map(msg -> MAPPER.readTree(msg.getBody()))
          .filter(e -> type.equals(e.get("eventType").asText()))
          .toList();
    }
  }

  // Client STOMP gom frame JSON vào hàng đợi, frame Probe chỉ dùng để biết đã nghe được
  public static final class Client {

    public final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
    public final UUID userId;
    public final StompSession session;
    private final SimpMessagingTemplate template;
    private volatile boolean probed;

    // Gói template gửi STOMP của instance, user và session đã kết nối
    Client(SimpMessagingTemplate template, UUID userId, StompSession session) {
      this.template = template;
      this.userId = userId;
      this.session = session;
    }

    // Subscribe topic rồi gửi frame Probe lặp lại tới khi nhận được, chứng tỏ broker đã đăng ký
    public void subscribe(String destination) {
      session.subscribe(
          destination,
          new StompFrameHandler() {
            // Nhận payload thô để tự parse JSON
            @Override
            public Type getPayloadType(StompHeaders headers) {
              return byte[].class;
            }

            // Frame Probe chỉ bật cờ, frame khác xếp vào hàng đợi
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
              JsonNode frame = MAPPER.readTree((byte[]) payload);
              if ("Probe".equals(frame.get("type").asText())) {
                probed = true;
              } else {
                frames.add(frame);
              }
            }
          });
      Frame probe = new Frame("Probe", "p", "t", Map.of());
      probed = false;
      await()
          .atMost(5, TimeUnit.SECONDS)
          .pollInterval(100, TimeUnit.MILLISECONDS)
          .until(
              () -> {
                template.convertAndSend(destination, probe);
                return probed;
              });
    }

    // Frame kế tiếp, tối đa 5 giây
    public JsonNode next() throws Exception {
      JsonNode frame = frames.poll(5, TimeUnit.SECONDS);
      assertThat(frame).as("frame nhận được").isNotNull();
      return frame;
    }
  }
}
