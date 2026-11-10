package com.nexus.realtime.infrastructure.stomp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.realtime.FakeAccessServer;
import com.nexus.realtime.application.subscription.SubscriptionRegistry;
import com.nexus.testsupport.TestJwt;
import java.lang.reflect.Type;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

// IT xác thực CONNECT và kiểm quyền SUBSCRIBE/SEND bằng WebSocketStompClient thật
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "management.server.port=0",
      "app.grpc.service-token=dummy-realtime"
    })
class StompAuthIT {

  private static final TestJwt JWT = TestJwt.create();
  private static final TestJwt.JwksServer JWKS = JWT.serveJwks();
  private static final FakeAccessServer ACCESS = new FakeAccessServer();

  private final UUID userId = UUID.randomUUID();
  private final UUID channelId = UUID.randomUUID();
  private final UUID serverId = UUID.randomUUID();
  private final WebSocketStompClient client =
      new WebSocketStompClient(new StandardWebSocketClient());

  @LocalServerPort private int port;
  @Autowired private SubscriptionRegistry registry;
  @MockitoBean private RabbitTemplate rabbit;

  // Trỏ JWKS và hai target gRPC tới máy chủ giả
  @DynamicPropertySource
  static void fakes(DynamicPropertyRegistry registry) {
    registry.add("app.jwt.jwks-uri", JWKS::uri);
    registry.add("app.grpc.community.target", ACCESS::target);
    registry.add("app.grpc.messaging.target", ACCESS::target);
  }

  // Xóa cấu hình máy chủ giả trước mỗi test
  @BeforeEach
  void resetFake() {
    ACCESS.reset();
  }

  // Dừng client STOMP sau mỗi test
  @AfterEach
  void stopClient() {
    client.stop();
  }

  // Kịch bản: token hợp lệ
  // Input: CONNECT kèm Authorization Bearer của user hợp lệ
  // Output: nhận CONNECTED, session mở
  @Test
  void connectWithValidTokenSucceeds() throws Exception {
    Probe probe = connect(token(userId));
    assertThat(probe.connected.get(5, TimeUnit.SECONDS).isConnected()).isTrue();
  }

  // Kịch bản: thiếu header Authorization
  // Input: CONNECT không có Authorization
  // Output: nhận ERROR UNAUTHENTICATED, không có CONNECTED
  @Test
  void connectWithoutTokenIsRejected() throws Exception {
    Probe probe = connect(null);
    assertThat(probe.error.get(5, TimeUnit.SECONDS)).isEqualTo("UNAUTHENTICATED");
    assertThat(probe.connected.isDone()).isFalse();
  }

  // Kịch bản: token hết hạn hoặc ký bằng khóa lạ
  // Input: CONNECT với token hết hạn một giờ trước, rồi với token ký bằng khóa khác
  // Output: cả hai nhận ERROR UNAUTHENTICATED
  @Test
  void connectWithExpiredOrForgedTokenIsRejected() throws Exception {
    String expired = JWT.token(userId, "u", Instant.now().minus(Duration.ofHours(1)));
    String forged = TestJwt.create().token(userId, "u");
    assertThat(connect("Bearer " + expired).error.get(5, TimeUnit.SECONDS))
        .isEqualTo("UNAUTHENTICATED");
    assertThat(connect("Bearer " + forged).error.get(5, TimeUnit.SECONDS))
        .isEqualTo("UNAUTHENTICATED");
  }

  // Kịch bản: user xem được kênh
  // Input: SUBSCRIBE /topic/channels/{id} khi Messaging trả can_view=true
  // Output: registry có đúng một subscription của user với destination đó
  @Test
  void subscribeChannelWithViewSucceeds() throws Exception {
    ACCESS.allowView(userId, channelId);
    Probe probe = connectOk(userId);
    String destination = "/topic/channels/" + channelId;
    subscribe(probe, destination);
    await().atMost(5, TimeUnit.SECONDS).until(() -> !registry.byDestination(destination).isEmpty());
    assertThat(registry.byDestination(destination))
        .singleElement()
        .satisfies(s -> assertThat(s.userId()).isEqualTo(userId));
    assertThat(ACCESS.lastToken()).isEqualTo("dummy-realtime");
  }

  // Kịch bản: participant của DM subscribe kênh DM
  // Input: SUBSCRIBE /topic/channels/{dmId} khi Messaging trả can_view=true, is_dm=true
  // Output: registry có subscription của user với serverId null
  @Test
  void subscribeDmChannelStoresNullServerId() throws Exception {
    ACCESS.allowDm(userId, channelId);
    Probe probe = connectOk(userId);
    String destination = "/topic/channels/" + channelId;
    subscribe(probe, destination);
    await().atMost(5, TimeUnit.SECONDS).until(() -> !registry.byDestination(destination).isEmpty());
    assertThat(registry.byDestination(destination))
        .singleElement()
        .satisfies(s -> assertThat(s.serverId()).isNull());
  }

  // Kịch bản: người ngoài subscribe kênh DM của người khác
  // Input: SUBSCRIBE /topic/channels/{dmId} khi Messaging trả can_view=false
  // Output: nhận ERROR PERMISSION_DENIED, registry rỗng
  @Test
  void subscribeDmChannelAsOutsiderIsDenied() throws Exception {
    ACCESS.allowDm(UUID.randomUUID(), channelId);
    Probe probe = connectOk(userId);
    subscribe(probe, "/topic/channels/" + channelId);
    assertThat(probe.error.get(5, TimeUnit.SECONDS)).isEqualTo("PERMISSION_DENIED");
    assertThat(registry.byUser(userId)).isEmpty();
  }

  // Kịch bản: user không xem được kênh
  // Input: SUBSCRIBE /topic/channels/{id} khi Messaging trả can_view=false
  // Output: nhận ERROR PERMISSION_DENIED, registry rỗng
  @Test
  void subscribeChannelWithoutViewGetsErrorFrame() throws Exception {
    Probe probe = connectOk(userId);
    subscribe(probe, "/topic/channels/" + channelId);
    assertThat(probe.error.get(5, TimeUnit.SECONDS)).isEqualTo("PERMISSION_DENIED");
    assertThat(registry.byUser(userId)).isEmpty();
  }

  // Kịch bản: user không phải member server
  // Input: SUBSCRIBE /topic/servers/{id} khi Community trả is_member=false
  // Output: nhận ERROR PERMISSION_DENIED, registry rỗng
  @Test
  void subscribeServerAsNonMemberGetsErrorFrame() throws Exception {
    Probe probe = connectOk(userId);
    subscribe(probe, "/topic/servers/" + serverId);
    assertThat(probe.error.get(5, TimeUnit.SECONDS)).isEqualTo("PERMISSION_DENIED");
    assertThat(registry.byUser(userId)).isEmpty();
  }

  // Kịch bản: member server đăng ký topic server
  // Input: SUBSCRIBE /topic/servers/{id} khi Community trả is_member=true
  // Output: registry có subscription của user
  @Test
  void subscribeServerAsMemberSucceeds() throws Exception {
    ACCESS.allowMember(userId, serverId);
    Probe probe = connectOk(userId);
    subscribe(probe, "/topic/servers/" + serverId);
    await().atMost(5, TimeUnit.SECONDS).until(() -> !registry.byUser(userId).isEmpty());
    assertThat(ACCESS.lastToken()).isEqualTo("dummy-realtime");
  }

  // Kịch bản: destination không nằm trong danh sách cho phép
  // Input: SUBSCRIBE /topic/unknown
  // Output: nhận ERROR INVALID_DESTINATION, registry rỗng
  @Test
  void subscribeUnknownDestinationGetsErrorFrame() throws Exception {
    Probe probe = connectOk(userId);
    subscribe(probe, "/topic/unknown");
    assertThat(probe.error.get(5, TimeUnit.SECONDS)).isEqualTo("INVALID_DESTINATION");
    assertThat(registry.byUser(userId)).isEmpty();
  }

  // Kịch bản: gửi SUBSCRIBE thô khi chưa CONNECT
  // Input: frame SUBSCRIBE /user/queue/events gửi thẳng lên WebSocket
  // Output: nhận frame ERROR "Unknown session" từ Spring, không vào sổ
  @Test
  void subscribeBeforeConnectIsRejected() throws Exception {
    try (Raw raw = new Raw()) {
      raw.send("SUBSCRIBE\nid:s1\ndestination:/user/queue/events\n\n");
      assertThat(raw.next()).startsWith("ERROR").contains("Unknown session");
    }
  }

  // Kịch bản: CONNECT lần hai trên session đã xác thực
  // Input: hai frame CONNECT hợp lệ liên tiếp
  // Output: lần đầu nhận CONNECTED, lần hai nhận ERROR "Session already exists" của Spring
  @Test
  void secondConnectOnSameSessionIsRejected() throws Exception {
    try (Raw raw = new Raw()) {
      raw.send("CONNECT\naccept-version:1.2\nAuthorization:" + token(userId) + "\n\n");
      assertThat(raw.next()).startsWith("CONNECTED");
      raw.send("CONNECT\naccept-version:1.2\nAuthorization:" + token(userId) + "\n\n");
      assertThat(raw.next()).startsWith("ERROR").contains("Session already exists");
    }
  }

  // Kịch bản: SUBSCRIBE trùng subscription id trên destination khác
  // Input: SUBSCRIBE id s1 kênh A (được phép), rồi SUBSCRIBE id s1 kênh B (cũng được phép)
  // Output: lần hai nhận ERROR DUPLICATE_SUBSCRIPTION_ID
  @Test
  void reusedSubscriptionIdIsRejected() throws Exception {
    UUID otherChannel = UUID.randomUUID();
    ACCESS.allowView(userId, channelId);
    ACCESS.allowView(userId, otherChannel);
    try (Raw raw = new Raw()) {
      raw.send("CONNECT\naccept-version:1.2\nAuthorization:" + token(userId) + "\n\n");
      assertThat(raw.next()).startsWith("CONNECTED");
      raw.send("SUBSCRIBE\nid:s1\ndestination:/topic/channels/" + channelId + "\n\n");
      await().atMost(5, TimeUnit.SECONDS).until(() -> !registry.byUser(userId).isEmpty());
      raw.send("SUBSCRIBE\nid:s1\ndestination:/topic/channels/" + otherChannel + "\n\n");
      assertThat(raw.next()).startsWith("ERROR").contains("DUPLICATE_SUBSCRIPTION_ID");
    }
  }

  // Kịch bản: Messaging và Community không trả lời
  // Input: SUBSCRIBE /topic/channels/{id} khi gRPC trả UNAVAILABLE
  // Output: nhận ERROR DEPENDENCY_UNAVAILABLE, registry rỗng
  @Test
  void grpcDownMeansSubscribeDenied() throws Exception {
    ACCESS.allowView(userId, channelId);
    ACCESS.failing(true);
    Probe probe = connectOk(userId);
    subscribe(probe, "/topic/channels/" + channelId);
    assertThat(probe.error.get(5, TimeUnit.SECONDS)).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(registry.byUser(userId)).isEmpty();
  }

  // Kịch bản: gRPC chậm hơn deadline 500 ms
  // Input: SUBSCRIBE /topic/channels/{id} khi server trễ 1500 ms
  // Output: nhận ERROR DEPENDENCY_UNAVAILABLE
  @Test
  void slowGrpcMeansSubscribeDenied() throws Exception {
    ACCESS.allowView(userId, channelId);
    ACCESS.delay(1500);
    Probe probe = connectOk(userId);
    subscribe(probe, "/topic/channels/" + channelId);
    assertThat(probe.error.get(5, TimeUnit.SECONDS)).isEqualTo("DEPENDENCY_UNAVAILABLE");
  }

  // Kịch bản: SEND tới destination không phải typing
  // Input: SEND /topic/channels/{id}
  // Output: nhận ERROR INVALID_DESTINATION
  @Test
  void sendToOtherDestinationIsRejected() throws Exception {
    Probe probe = connectOk(userId);
    probe.connected.get().send("/topic/channels/" + channelId, "hi".getBytes());
    assertThat(probe.error.get(5, TimeUnit.SECONDS)).isEqualTo("INVALID_DESTINATION");
  }

  // Kịch bản: UNSUBSCRIBE một subscription
  // Input: SUBSCRIBE rồi UNSUBSCRIBE /topic/channels/{id}
  // Output: registry không còn subscription của user
  @Test
  void unsubscribeRemovesFromRegistry() throws Exception {
    ACCESS.allowView(userId, channelId);
    Probe probe = connectOk(userId);
    StompSession.Subscription subscription = subscribe(probe, "/topic/channels/" + channelId);
    await().atMost(5, TimeUnit.SECONDS).until(() -> !registry.byUser(userId).isEmpty());
    subscription.unsubscribe();
    await().atMost(5, TimeUnit.SECONDS).until(() -> registry.byUser(userId).isEmpty());
  }

  // Kịch bản: client ngắt kết nối
  // Input: hai SUBSCRIBE rồi DISCONNECT
  // Output: registry không còn subscription nào của session
  @Test
  void disconnectRemovesAllSubscriptions() throws Exception {
    ACCESS.allowView(userId, channelId);
    ACCESS.allowMember(userId, serverId);
    Probe probe = connectOk(userId);
    subscribe(probe, "/topic/channels/" + channelId);
    subscribe(probe, "/topic/servers/" + serverId);
    await().atMost(5, TimeUnit.SECONDS).until(() -> registry.byUser(userId).size() == 2);
    probe.connected.get().disconnect();
    await().atMost(5, TimeUnit.SECONDS).until(() -> registry.byUser(userId).isEmpty());
  }

  // Kịch bản: kết nối đứt đột ngột không kịp gửi DISCONNECT
  // Input: frame CONNECT và SUBSCRIBE thô rồi đóng WebSocket
  // Output: registry không còn subscription của user
  @Test
  void abruptCloseRemovesSubscriptions() throws Exception {
    ACCESS.allowView(userId, channelId);
    try (Raw raw = new Raw()) {
      raw.send("CONNECT\naccept-version:1.2\nAuthorization:" + token(userId) + "\n\n");
      assertThat(raw.next()).startsWith("CONNECTED");
      raw.send("SUBSCRIBE\nid:s1\ndestination:/topic/channels/" + channelId + "\n\n");
      await().atMost(5, TimeUnit.SECONDS).until(() -> !registry.byUser(userId).isEmpty());
    }
    await().atMost(5, TimeUnit.SECONDS).until(() -> registry.byUser(userId).isEmpty());
  }

  // Tạo header Authorization Bearer cho user
  private static String token(UUID id) {
    return "Bearer " + JWT.token(id, "user");
  }

  // Kết nối với header Authorization cho trước (null thì không gửi) và trả probe ghi kết quả
  private Probe connect(String authorization) {
    Probe probe = new Probe();
    StompHeaders connectHeaders = new StompHeaders();
    if (authorization != null) {
      connectHeaders.add("Authorization", authorization);
    }
    client.connectAsync(
        "ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connectHeaders, probe);
    return probe;
  }

  // Kết nối bằng token hợp lệ của user và chờ CONNECTED
  private Probe connectOk(UUID id) throws Exception {
    Probe probe = connect(token(id));
    probe.connected.get(5, TimeUnit.SECONDS);
    return probe;
  }

  // Gửi SUBSCRIBE tới destination, bỏ qua mọi message nhận về
  private static StompSession.Subscription subscribe(Probe probe, String destination)
      throws Exception {
    return probe
        .connected
        .get()
        .subscribe(
            destination,
            new StompFrameHandler() {
              // Kiểu payload mặc định là byte[]
              @Override
              public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
              }

              // Test này không quan tâm message nhận về
              @Override
              public void handleFrame(StompHeaders headers, Object payload) {}
            });
  }

  // Kết nối WebSocket thô gửi frame STOMP bằng tay và xếp hàng frame server trả về
  private final class Raw implements AutoCloseable {

    private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private final WebSocketSession session;

    // Mở WebSocket tới /ws với subprotocol STOMP
    Raw() throws Exception {
      WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
      headers.setSecWebSocketProtocol("v12.stomp");
      session =
          new StandardWebSocketClient()
              .execute(
                  new TextWebSocketHandler() {
                    // Xếp frame nhận được vào hàng đợi
                    @Override
                    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                      frames.add(message.getPayload());
                    }
                  },
                  headers,
                  URI.create("ws://localhost:" + port + "/ws"))
              .get(5, TimeUnit.SECONDS);
    }

    // Gửi một frame STOMP, tự thêm byte kết thúc NUL
    void send(String frame) throws Exception {
      session.sendMessage(new TextMessage(frame + "\u0000"));
    }

    // Frame kế tiếp server gửi về, tối đa 5 giây
    String next() throws Exception {
      return frames.poll(5, TimeUnit.SECONDS);
    }

    // Đóng WebSocket
    @Override
    public void close() throws Exception {
      session.close();
    }
  }

  // Ghi lại CONNECTED và header message của frame ERROR
  private static final class Probe extends StompSessionHandlerAdapter {

    final CompletableFuture<StompSession> connected = new CompletableFuture<>();
    final CompletableFuture<String> error = new CompletableFuture<>();

    // Báo đã nhận CONNECTED
    @Override
    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
      connected.complete(session);
    }

    // Ghi lý do trong header message của frame ERROR
    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      error.complete(headers.getFirst("message"));
    }
  }
}
