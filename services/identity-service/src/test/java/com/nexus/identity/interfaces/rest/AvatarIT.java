package com.nexus.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.IdentityIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra endpoint avatar /api/v1/users/me/avatar với Postgres thật qua HTTP
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AvatarIT extends IdentityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final String PASSWORD = "secret-pass-1";

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // Kịch bản: chữ ký trả về gắn với folder của người gọi và không lộ secret
  // Input: POST /users/me/avatar/signature có Bearer; không có Bearer
  // Output: 200 folder nexus-test/avatars/{id}, chữ ký 40 hex, không chứa secret; không Bearer 401
  @Test
  void signatureEndpointReturnsFolderBoundToCaller() throws Exception {
    String tag = tag();
    String id = register(tag);

    HttpResponse<String> response =
        send("POST", "/api/v1/users/me/avatar/signature", null, login(tag));
    HttpResponse<String> anonymous = send("POST", "/api/v1/users/me/avatar/signature", null, null);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.get("folder").asText()).isEqualTo(TEST_ROOT + "/avatars/" + id);
    assertThat(body.get("cloudName").asText()).isEqualTo(TEST_CLOUD);
    assertThat(body.get("uploadPreset").asText()).isEqualTo("test_preset");
    assertThat(body.get("signature").asText()).matches("[0-9a-f]{40}");
    assertThat(response.body()).doesNotContain(TEST_SECRET);
    assertThat(anonymous.statusCode()).isEqualTo(401);
  }

  // Kịch bản: publicId thuộc folder của user khác bị từ chối
  // Input: PUT /users/me/avatar với publicId trong folder của một user khác
  // Output: 400 VALIDATION_FAILED
  @Test
  void putAvatarWithOtherUserFolderReturns400() throws Exception {
    String tag = tag();
    register(tag);

    HttpResponse<String> response =
        put(login(tag), TEST_ROOT + "/avatars/" + UUID.randomUUID() + "/pic");

    assertValidationFailed(response);
  }

  // Kịch bản: publicId chứa .. để thoát folder bị từ chối
  // Input: PUT /users/me/avatar với publicId {folder của mình}/../x
  // Output: 400 VALIDATION_FAILED
  @Test
  void putAvatarWithDotDotReturns400() throws Exception {
    String tag = tag();
    String id = register(tag);

    HttpResponse<String> response =
        put(login(tag), TEST_ROOT + "/avatars/" + id + "/../" + UUID.randomUUID() + "/pic");

    assertValidationFailed(response);
  }

  // Kịch bản: publicId hợp lệ được lưu và ghi outbox user.profile-updated
  // Input: PUT /users/me/avatar với publicId trong folder của mình, rồi GET /me
  // Output: 200 avatarUrl res.cloudinary.com, /me trả cùng url, outbox có avatarUrl đó
  @Test
  void putAvatarStoresAndWritesProfileUpdatedOutbox() throws Exception {
    String tag = tag();
    String id = register(tag);
    String token = login(tag);
    String publicId = TEST_ROOT + "/avatars/" + id + "/pic_1.v-2";
    String url = "https://res.cloudinary.com/" + TEST_CLOUD + "/image/upload/" + publicId;

    HttpResponse<String> response = put(token, publicId);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(response.body()).get("avatarUrl").asText()).isEqualTo(url);
    JsonNode me = MAPPER.readTree(send("GET", "/api/v1/users/me", null, token).body());
    assertThat(me.get("avatarUrl").asText()).isEqualTo(url);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select payload::text as payload from identity.outbox_event"
                + " where routing_key = 'user.profile-updated' and aggregate_id = ?::uuid",
            id);
    assertThat(rows).hasSize(1);
    assertThat(MAPPER.readTree((String) rows.get(0).get("payload")).get("avatarUrl").asText())
        .isEqualTo(url);
  }

  // Kiểm tra phản hồi là 400 VALIDATION_FAILED
  private static void assertValidationFailed(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(response.body()).get("code").asText())
        .isEqualTo("VALIDATION_FAILED");
  }

  // Gửi PUT /users/me/avatar với publicId
  private HttpResponse<String> put(String token, String publicId) throws Exception {
    return send(
        "PUT",
        "/api/v1/users/me/avatar",
        MAPPER.writeValueAsString(Map.of("publicId", publicId)),
        token);
  }

  // Sinh tag ngẫu nhiên làm email và username
  private static String tag() {
    return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  // Đăng ký user tag@x.io và trả id của user
  private String register(String tag) throws Exception {
    HttpResponse<String> response =
        send(
            "POST",
            "/api/v1/auth/register",
            MAPPER.writeValueAsString(
                Map.of("email", tag + "@x.io", "username", tag, "password", PASSWORD)),
            null);
    assertThat(response.statusCode()).isEqualTo(201);
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Đăng nhập user tag@x.io và trả access token
  private String login(String tag) throws Exception {
    HttpResponse<String> response =
        send(
            "POST",
            "/api/v1/auth/login",
            MAPPER.writeValueAsString(Map.of("email", tag + "@x.io", "password", PASSWORD)),
            null);
    assertThat(response.statusCode()).isEqualTo(200);
    return MAPPER.readTree(response.body()).get("accessToken").asText();
  }

  // Gửi request JSON tới ứng dụng, body và Bearer khác null thì mới gửi kèm
  private HttpResponse<String> send(String method, String path, String json, String bearer)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
