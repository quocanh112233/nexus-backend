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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra ban và accept lời mời đồng thời qua HTTP thật không bao giờ để lại member bị ban
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BanJoinConcurrencyIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final int ROUNDS = 50;
  private static final int ACCEPTS_PER_ROUND = 3;
  private static final long TIMEOUT_SECONDS = 30;

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // Kịch bản: mỗi vòng một user mới, owner ban và user accept lời mời cùng lúc, lặp 50 vòng
  // Input: invite maxUses 0; mỗi vòng 1 PUT ban và 3 POST accept của một user sau CountDownLatch
  // Output: ban luôn 204 và có dòng server_bans; accept chỉ 200 hoặc 403; không vòng nào còn member
  @Test
  void banRacingWithJoinNeverLeavesBannedMember() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String code = createInvite(server, owner);
    ExecutorService pool = Executors.newFixedThreadPool(1 + ACCEPTS_PER_ROUND);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        race(pool, server, code, owner, UUID.randomUUID(), round);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // Chạy một vòng: ban và các accept bắt đầu cùng lúc, rồi kiểm mã trả về và trạng thái DB
  private void race(
      ExecutorService pool, String server, String code, UUID owner, UUID user, int round)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(1 + ACCEPTS_PER_ROUND);
    CountDownLatch start = new CountDownLatch(1);
    Future<Integer> ban =
        pool.submit(gated(ready, start, () -> put("/servers/" + server + "/bans/" + user, owner)));
    List<Future<Integer>> accepts = new ArrayList<>();
    for (int i = 0; i < ACCEPTS_PER_ROUND; i++) {
      accepts.add(
          pool.submit(gated(ready, start, () -> post("/invites/" + code + "/accept", user))));
    }
    assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    start.countDown();

    assertThat(ban.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("ban vòng %d", round).isEqualTo(204);
    for (Future<Integer> accept : accepts) {
      assertThat(accept.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("accept vòng %d", round)
          .isIn(200, 403);
    }
    assertThat(rows("community.server_bans", server, user)).as("ban vòng %d", round).isEqualTo(1);
    assertThat(rows("community.members", server, user)).as("member bị ban vòng %d", round).isZero();
  }

  // Bọc việc gọi HTTP: báo sẵn sàng rồi chờ cổng mở mới bắn, trả mã trạng thái
  private static Callable<Integer> gated(
      CountDownLatch ready, CountDownLatch start, Callable<Integer> call) {
    return () -> {
      ready.countDown();
      assertThat(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      return call.call();
    };
  }

  // Tạo server qua REST và trả id
  private String createServer(UUID owner) throws Exception {
    HttpResponse<String> response = send("POST", "/servers", owner, "{\"name\":\"Race\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Tạo lời mời không giới hạn lượt (maxUses 0) và trả mã
  private String createInvite(String server, UUID owner) throws Exception {
    HttpResponse<String> response =
        send("POST", "/servers/" + server + "/invites", owner, "{\"maxUses\":0}");
    assertThat(response.statusCode()).isEqualTo(201);
    return MAPPER.readTree(response.body()).get("code").asText();
  }

  // PUT không body với Bearer của user, trả mã trạng thái
  private int put(String path, UUID user) throws Exception {
    return send("PUT", path, user, null).statusCode();
  }

  // POST không body với Bearer của user, trả mã trạng thái
  private int post(String path, UUID user) throws Exception {
    return send("POST", path, user, null).statusCode();
  }

  // Gửi request tới /api/v1 + path với Bearer của user, body null là không gửi body
  private HttpResponse<String> send(String method, String path, UUID user, String json)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", bearer(user));
    if (json != null) {
      builder.header("Content-Type", "application/json");
    }
    return HTTP.send(
        builder
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // Đếm dòng của user trong bảng (members hoặc server_bans) thuộc server
  private long rows(String table, String server, UUID user) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where server_id = ?::uuid and user_id = ?",
        Long.class,
        server,
        user);
  }
}
