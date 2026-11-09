package com.nexus.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.nexus.testsupport.RedisContainer;
import com.nexus.testsupport.TestJwt;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

// Kiểm tra route realtime (WebSocket) và presence trên gateway thật với upstream Netty nhúng
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayWebSocketIT {

  private static final String ORIGIN = "http://localhost:3000";
  private static final TestJwt JWT = TestJwt.create();
  private static final TestJwt.JwksServer JWKS = JWT.serveJwks();
  private static final AtomicInteger HANDSHAKES = new AtomicInteger();
  private static final DisposableServer UPSTREAM = upstream();

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  // Trỏ realtime và presence vào upstream nhúng, bật rate limit với Redis thật
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String uri = "http://127.0.0.1:" + UPSTREAM.port();
    registry.add("REALTIME_URI", () -> "127.0.0.1:" + UPSTREAM.port());
    registry.add("PRESENCE_URI", () -> uri);
    registry.add("IDENTITY_URI", () -> uri);
    registry.add("COMMUNITY_URI", () -> uri);
    registry.add("MESSAGING_URI", () -> uri);
    registry.add("JWKS_URI", JWKS::uri);
    registry.add("CORS_ALLOWED_ORIGINS", () -> ORIGIN);
    registry.add("management.server.port", () -> "0");
    registry.add("GATEWAY_RESPONSE_TIMEOUT", () -> "2s");
    registry.add("app.ratelimit.enabled", () -> "true");
    registry.add("REDIS_HOST", RedisContainer::host);
    registry.add("REDIS_PORT", RedisContainer::port);
  }

  // Dừng upstream và máy chủ JWKS sau khi chạy hết test
  @AfterAll
  static void stop() {
    UPSTREAM.disposeNow();
    JWKS.close();
  }

  // Kịch bản: handshake WebSocket không có Bearer vẫn được proxy tới realtime-gateway
  // Input: ws://gateway/ws không Authorization, Origin được phép
  // Output: kết nối mở được và upstream đếm thêm một handshake
  @Test
  void wsUpgradeIsProxiedWithoutBearer() throws Exception {
    int before = HANDSHAKES.get();

    WebSocket socket = connect(ORIGIN, new Frames());

    assertThat(socket.isOutputClosed()).isFalse();
    await().atMost(5, TimeUnit.SECONDS).until(() -> HANDSHAKES.get() == before + 1);
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
  }

  // Kịch bản: frame STOMP đi và về qua gateway, xác thực để dành cho frame CONNECT
  // Input: gửi CONNECT kèm header Authorization rồi một frame SUBSCRIBE
  // Output: nhận CONNECTED rồi frame SUBSCRIBE được upstream phản hồi nguyên văn
  @Test
  void stompFrameRoundTripThroughGateway() throws Exception {
    Frames frames = new Frames();
    WebSocket socket = connect(ORIGIN, frames);

    socket.sendText("CONNECT\naccept-version:1.2\nAuthorization:Bearer x\n\n\0", true).get();
    assertThat(frames.next()).startsWith("CONNECTED");
    socket.sendText("SUBSCRIBE\nid:0\ndestination:/topic/servers/s1\n\n\0", true).get();

    assertThat(frames.next())
        .isEqualTo("ECHO SUBSCRIBE\nid:0\ndestination:/topic/servers/s1\n\n\0");
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
  }

  // Kịch bản: socket nhàn rỗi lâu hơn response-timeout của gateway (test đặt 2 s) vẫn sống
  // Input: mở /ws, im lặng 4 giây rồi gửi một frame SUBSCRIBE
  // Output: frame vẫn được upstream phản hồi, socket chưa bị gateway đóng
  @Test
  void idleWebSocketSurvivesGatewayResponseTimeout() throws Exception {
    Frames frames = new Frames();
    WebSocket socket = connect(ORIGIN, frames);

    Thread.sleep(4000);
    socket.sendText("SUBSCRIBE\nid:1\ndestination:/topic/servers/s2\n\n\0", true).get();

    assertThat(frames.next()).startsWith("ECHO SUBSCRIBE");
    assertThat(socket.isInputClosed()).isFalse();
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
  }

  // Kịch bản: Origin lạ không được mở WebSocket qua gateway, CORS vẫn áp dụng cho handshake
  // Input: ws://gateway/ws với Origin https://evil.example
  // Output: handshake thất bại với HTTP 403 và upstream không đếm thêm handshake
  @Test
  void wsUpgradeWithForeignOriginIsRejected() {
    int before = HANDSHAKES.get();

    assertThatThrownBy(() -> connect("https://evil.example", new Frames()))
        .rootCause()
        .hasMessageContaining("403");
    assertThat(HANDSHAKES.get()).isEqualTo(before);
  }

  // Kịch bản: mở /ws không kéo theo route REST nào thành công khai
  // Input: GET servers, channels và presence không Bearer; GET servers có Bearer hợp lệ
  // Output: ba request đầu 401, request có Bearer 200
  @Test
  void restRoutesStillRequireBearer() throws Exception {
    assertThat(get("/api/v1/servers", null).statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/channels/c1/messages", null).statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/presence", null).statusCode()).isEqualTo(401);

    assertThat(get("/api/v1/servers", token()).statusCode()).isEqualTo(200);
  }

  // Kịch bản: route presence cần Bearer và chịu rate limit theo sub như API khác
  // Input: GET /api/v1/presence không token; rồi 130 lần liên tiếp cùng một user
  // Output: không token 401; 100 lần đầu 200, sau đó có 429 RATE_LIMITED kèm Retry-After
  @Test
  void presenceRouteRequiresBearerAndIsRateLimited() throws Exception {
    assertThat(get("/api/v1/presence", null).statusCode()).isEqualTo(401);
    String user = token();

    List<HttpResponse<String>> responses = new ArrayList<>();
    for (int i = 0; i < 130; i++) {
      responses.add(get("/api/v1/presence", user));
    }

    assertThat(responses.subList(0, 100))
        .allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
    HttpResponse<String> limited =
        responses.subList(100, 130).stream()
            .filter(r -> r.statusCode() == 429)
            .findFirst()
            .orElseThrow();
    assertThat(limited.body()).contains("RATE_LIMITED");
    assertThat(limited.headers().firstValue("Retry-After")).isPresent();
  }

  // Kịch bản: 200 UUID trong query (khoảng 7,5 KB URL) vẫn qua gateway tới presence-service
  // Input: GET /api/v1/presence?userIds=<200 uuid> kèm Bearer hợp lệ
  // Output: 200 và upstream nhận đủ query dài hơn 7000 ký tự
  @Test
  void presenceRoutePassesTwoHundredIdsInQuery() throws Exception {
    String ids =
        IntStream.range(0, 200)
            .mapToObj(i -> UUID.randomUUID().toString())
            .collect(Collectors.joining(","));

    HttpResponse<String> response = get("/api/v1/presence?userIds=" + ids, token());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(Integer.parseInt(response.body())).isGreaterThan(7000);
  }

  // Dựng upstream Netty: /ws là STOMP giả, /api/** trả độ dài URI, nới giới hạn dòng đầu
  private static DisposableServer upstream() {
    BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> length =
        (req, res) -> res.sendString(Mono.just(String.valueOf(req.uri().length())));
    return HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .httpRequestDecoder(spec -> spec.maxInitialLineLength(16384))
        .route(
            routes ->
                routes
                    .ws(
                        "/ws",
                        (in, out) -> {
                          HANDSHAKES.incrementAndGet();
                          return out.sendString(
                              in.receive().asString().map(GatewayWebSocketIT::stomp));
                        })
                    .get("/api/v1/servers", length)
                    .get("/api/v1/presence", length)
                    .get("/api/v1/channels/{id}/messages", length))
        .bindNow();
  }

  // Phản hồi STOMP giả: CONNECT thì CONNECTED, frame khác được lặp lại sau tiền tố ECHO
  private static String stomp(String frame) {
    return frame.startsWith("CONNECT") ? "CONNECTED\nversion:1.2\n\n\0" : "ECHO " + frame;
  }

  // Mở WebSocket qua gateway với Origin cho trước, không gửi Authorization
  private WebSocket connect(String origin, Frames frames) throws Exception {
    try {
      return http.newWebSocketBuilder()
          .header("Origin", origin)
          .buildAsync(URI.create("ws://localhost:" + port + "/ws"), frames)
          .get(10, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      throw new CompletionException(e.getCause());
    }
  }

  // GET đường dẫn qua gateway, token null thì không gửi Authorization
  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  // Token hợp lệ của một user mới để mỗi test có bucket rate limit riêng
  private static String token() {
    return JWT.token(UUID.randomUUID(), "alice");
  }

  // Listener gom frame text về hàng đợi theo thứ tự nhận
  private static final class Frames implements WebSocket.Listener {

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final StringBuilder partial = new StringBuilder();

    // Cho phép nhận frame đầu tiên khi socket mở
    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    // Ghép các mảnh text thành frame đầy đủ rồi xin frame tiếp theo
    @Override
    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        queue.add(partial.toString());
        partial.setLength(0);
      }
      webSocket.request(1);
      return null;
    }

    // Chờ frame kế tiếp tối đa 10 giây
    String next() throws InterruptedException {
      String frame = queue.poll(10, TimeUnit.SECONDS);
      assertThat(frame).as("frame từ gateway").isNotNull();
      return frame;
    }
  }
}
