package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra accept lời mời đồng thời qua HTTP thật không vượt maxUses và không đếm trùng
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InviteConcurrencyIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // Kịch bản: 20 user khác nhau cùng accept lời mời 5 lượt
  // Input: maxUses 5, 20 POST accept đồng thời sau cổng CountDownLatch
  // Output: 5×200, 15×410; members = owner + 5; uses = 5; outbox member.joined = 1 + 5
  @Test
  void twentyUsersOnMaxUses5YieldExactlyFiveNewMembers() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String code = createInvite(server, owner, "{\"maxUses\":5}");
    List<UUID> users = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      users.add(UUID.randomUUID());
    }

    List<Integer> statuses = acceptConcurrently(code, users);

    assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(5);
    assertThat(statuses.stream().filter(s -> s == 410).count()).isEqualTo(15);
    assertThat(count("select count(*) from community.members where server_id = ?::uuid", server))
        .isEqualTo(6);
    assertThat(uses(code)).isEqualTo(5);
    assertThat(joinedOutbox(server)).isEqualTo(6);
  }

  // Kịch bản: cùng một user accept hai lần đồng thời chỉ được đếm một lần
  // Input: lời mời 5 lượt, hai POST accept đồng thời của cùng user
  // Output: cả hai 200; uses = 1; thêm đúng 1 member; outbox member.joined = 1 + 1
  @Test
  void sameUserConcurrentAcceptCountsOnce() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    String server = createServer(owner);
    String code = createInvite(server, owner, "{\"maxUses\":5}");

    List<Integer> statuses = acceptConcurrently(code, List.of(user, user));

    assertThat(statuses).containsExactly(200, 200);
    assertThat(uses(code)).isEqualTo(1);
    assertThat(count("select count(*) from community.members where server_id = ?::uuid", server))
        .isEqualTo(2);
    assertThat(joinedOutbox(server)).isEqualTo(2);
  }

  // Bắn accept cho mọi user cùng lúc qua cổng CountDownLatch, trả mã trạng thái theo thứ tự
  private List<Integer> acceptConcurrently(String code, List<UUID> users) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(users.size());
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (UUID user : users) {
        HttpRequest request =
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/v1/invites/" + code + "/accept"))
                .header("Authorization", bearer(user))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
                }));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> f : futures) {
        statuses.add(f.get());
      }
      return statuses;
    } finally {
      pool.shutdownNow();
    }
  }

  // Tạo server qua REST và trả id
  private String createServer(UUID owner) throws Exception {
    return MAPPER
        .readTree(post("/api/v1/servers", owner, "{\"name\":\"Race\"}").body())
        .get("id")
        .asText();
  }

  // Tạo lời mời qua REST và trả mã
  private String createInvite(String server, UUID owner, String body) throws Exception {
    HttpResponse<String> response = post("/api/v1/servers/" + server + "/invites", owner, body);
    assertThat(response.statusCode()).isEqualTo(201);
    return MAPPER.readTree(response.body()).get("code").asText();
  }

  // Gửi POST JSON với Bearer của user
  private HttpResponse<String> post(String path, UUID user, String json) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer(user))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // Đọc uses của lời mời
  private int uses(String code) {
    return jdbc.queryForObject(
        "select uses from community.invites where code = ?", Integer.class, code);
  }

  // Đếm dòng outbox member.joined của server
  private long joinedOutbox(String server) {
    return count(
        "select count(*) from community.outbox_event"
            + " where routing_key = 'member.joined' and payload->>'serverId' = ?",
        server);
  }

  // Chạy câu đếm với một tham số
  private long count(String sql, String arg) {
    return jdbc.queryForObject(sql, Long.class, arg);
  }
}
