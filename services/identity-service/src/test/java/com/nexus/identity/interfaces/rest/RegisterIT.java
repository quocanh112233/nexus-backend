package com.nexus.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.IdentityIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra POST /api/v1/auth/register với Postgres thật qua HTTP trên cổng ngẫu nhiên
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegisterIT extends IdentityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String PASSWORD = "secret-pass-1";

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // Kịch bản: đăng ký hợp lệ trả 201 kèm Location và ghi một dòng outbox user.registered
  // Input: POST register với email, username ngẫu nhiên và không có displayName
  // Output: 201, Location /api/v1/users/{id}, body không lộ mật khẩu, 1 dòng outbox cho user đó
  @Test
  void registerReturns201AndOutboxRow() throws Exception {
    String tag = tag();

    HttpResponse<String> response = register(tag + "@x.io", tag, null, PASSWORD);

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode body = MAPPER.readTree(response.body());
    String id = body.get("id").asText();
    assertThat(response.headers().firstValue("Location")).contains("/api/v1/users/" + id);
    assertThat(body.get("displayName").asText()).isEqualTo(tag);
    assertThat(body.has("password")).isFalse();
    assertThat(response.body()).doesNotContain(PASSWORD);
    Integer rows =
        jdbc.queryForObject(
            "select count(*) from identity.outbox_event where routing_key = 'user.registered'"
                + " and aggregate_id = ?::uuid",
            Integer.class,
            id);
    assertThat(rows).isEqualTo(1);
  }

  // Kịch bản: email trùng khác hoa thường trả 409 EMAIL_TAKEN và outbox không thêm dòng nào
  // Input: đăng ký a@x.io rồi A@X.IO với username khác
  // Output: lần hai 409 EMAIL_TAKEN, số dòng outbox_event không đổi giữa hai lần
  @Test
  void duplicateEmailDifferentCaseReturns409EmailTaken() throws Exception {
    String tag = tag();
    register(tag + "@x.io", tag, null, PASSWORD);
    int before = outboxCount();

    HttpResponse<String> response =
        register(tag.toUpperCase() + "@X.IO", tag + "2", null, PASSWORD);

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(MAPPER.readTree(response.body()).get("code").asText()).isEqualTo("EMAIL_TAKEN");
    assertThat(outboxCount()).isEqualTo(before);
  }

  // Kịch bản: username trùng trả 409 USERNAME_TAKEN và outbox không thêm dòng nào
  // Input: đăng ký cùng username với hai email khác nhau
  // Output: lần hai 409 USERNAME_TAKEN, số dòng outbox_event không đổi giữa hai lần
  @Test
  void duplicateUsernameReturns409UsernameTaken() throws Exception {
    String tag = tag();
    register(tag + "@x.io", tag, null, PASSWORD);
    int before = outboxCount();

    HttpResponse<String> response = register(tag + "b@x.io", tag, null, PASSWORD);

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(MAPPER.readTree(response.body()).get("code").asText()).isEqualTo("USERNAME_TAKEN");
    assertThat(outboxCount()).isEqualTo(before);
  }

  // Kịch bản: mật khẩu ngắn bị chặn bởi Bean Validation kèm lỗi theo field
  // Input: password 3 ký tự
  // Output: 400 VALIDATION_FAILED có errors chứa field password
  @Test
  void shortPasswordReturns400WithFieldError() throws Exception {
    String tag = tag();

    HttpResponse<String> response = register(tag + "@x.io", tag, null, "abc");

    assertThat(response.statusCode()).isEqualTo(400);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(body.get("errors").get(0).get("field").asText()).isEqualTo("password");
  }

  // Kịch bản: lỗi miền DomainValidationException hiện ra 400 chứ không phải 500
  // Input: username "bad name!" có khoảng trắng bị Username từ chối nhưng qua được Bean Validation
  // Output: 400 VALIDATION_FAILED
  @Test
  void domainValidationErrorSurfacesAs400() throws Exception {
    String tag = tag();

    HttpResponse<String> response = register(tag + "@x.io", "bad name!", null, PASSWORD);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(response.body()).get("code").asText())
        .isEqualTo("VALIDATION_FAILED");
  }

  // Kịch bản: hai request song song cùng username khác email chỉ tạo được một user
  // Input: 2 thread cùng username, email khác nhau, chạy cùng lúc
  // Output: đúng một 201, một 409 USERNAME_TAKEN, bảng users có 1 dòng cho username đó
  @Test
  void concurrentSameUsernameOneCreatedOneConflict() throws Exception {
    String tag = tag();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<HttpResponse<String>>> tasks =
          List.of(
              () -> {
                start.await();
                return register(tag + "1@x.io", tag, null, PASSWORD);
              },
              () -> {
                start.await();
                return register(tag + "2@x.io", tag, null, PASSWORD);
              });
      List<Future<HttpResponse<String>>> futures = tasks.stream().map(pool::submit).toList();
      start.countDown();
      List<Integer> statuses =
          List.of(futures.get(0).get().statusCode(), futures.get(1).get().statusCode());

      assertThat(statuses).containsExactlyInAnyOrder(201, 409);
      Integer users =
          jdbc.queryForObject(
              "select count(*) from identity.users where username = ?", Integer.class, tag);
      assertThat(users).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  // Kịch bản: mật khẩu chỉ được lưu dạng băm BCrypt
  // Input: đăng ký rồi đọc cột password_hash
  // Output: password_hash bắt đầu bằng $2 và không chứa mật khẩu thô
  @Test
  void passwordIsNeverStoredInPlaintext() throws Exception {
    String tag = tag();
    register(tag + "@x.io", tag, null, PASSWORD);

    String hash =
        jdbc.queryForObject(
            "select password_hash from identity.users where username = ?", String.class, tag);

    assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
  }

  // Sinh chuỗi ngẫu nhiên hợp lệ cho username và phần đầu email
  private static String tag() {
    return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  // Đếm số dòng outbox user.registered hiện có
  private int outboxCount() {
    return jdbc.queryForObject(
        "select count(*) from identity.outbox_event where routing_key = 'user.registered'",
        Integer.class);
  }

  // Gọi POST /api/v1/auth/register với body JSON, displayName null thì bỏ khỏi body
  private HttpResponse<String> register(
      String email, String username, String displayName, String password) throws Exception {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("email", email);
    body.put("username", username);
    if (displayName != null) {
      body.put("displayName", displayName);
    }
    body.put("password", password);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
