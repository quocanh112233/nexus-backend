package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra POST, PATCH, DELETE category với phân quyền chủ server và outbox
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CategoryIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // Kịch bản: chủ server tạo, đổi tên, đổi vị trí, xóa category và outbox ghi đủ ba loại event
  // Input: POST {"name":" Voice "}, PATCH tên, PATCH vị trí, PATCH cả hai, DELETE
  // Output: 201 vị trí 1; PATCH 200; PATCH hai trường một updated; 204; outbox 1/3/1 (c/u/d)
  @Test
  void ownerCreatesRenamesMovesDeletes() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);

    HttpResponse<String> created =
        call("POST", server + "/categories", owner, "{\"name\":\" Voice \"}");
    JsonNode c = MAPPER.readTree(created.body());
    String id = c.get("id").asText();
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(created.headers().firstValue("Location"))
        .contains("/api/v1/servers/" + server + "/categories/" + id);
    assertThat(c.get("serverId").asText()).isEqualTo(server);
    assertThat(c.get("name").asText()).isEqualTo("Voice");
    assertThat(c.get("position").asInt()).isEqualTo(1);

    String path = server + "/categories/" + id;
    HttpResponse<String> renamed = call("PATCH", path, owner, "{\"name\":\"Talk\"}");
    assertThat(renamed.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(renamed.body()).get("name").asText()).isEqualTo("Talk");
    HttpResponse<String> moved = call("PATCH", path, owner, "{\"position\":7}");
    assertThat(MAPPER.readTree(moved.body()).get("position").asInt()).isEqualTo(7);
    assertThat(MAPPER.readTree(moved.body()).get("name").asText()).isEqualTo("Talk");
    call("PATCH", path, owner, "{\"name\":\"Both\",\"position\":3}");

    assertThat(call("DELETE", path, owner, null).statusCode()).isEqualTo(204);

    assertThat(outbox("category.created", id)).hasSize(1);
    List<String> updated = outbox("category.updated", id);
    assertThat(updated).hasSize(3);
    assertThat(MAPPER.readTree(updated.get(2)).get("name").asText()).isEqualTo("Both");
    assertThat(MAPPER.readTree(updated.get(2)).get("position").asInt()).isEqualTo(3);
    assertThat(outbox("category.deleted", id)).hasSize(1);
    assertThat(MAPPER.readTree(call("GET", server, owner, null).body()).get("categories"))
        .hasSize(1);
  }

  // Kịch bản: thành viên không phải chủ bị chặn ở cả ba thao tác
  // Input: user B là thành viên POST, PATCH, DELETE category
  // Output: cả ba 403 PERMISSION_DENIED và category không đổi
  @Test
  void memberNonOwnerGets403() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String server = createServer(owner);
    addMember(server, member);
    String category = firstCategory(server, owner);
    String path = server + "/categories/" + category;

    List<HttpResponse<String>> responses =
        List.of(
            call("POST", server + "/categories", member, "{\"name\":\"X\"}"),
            call("PATCH", path, member, "{\"name\":\"X\"}"),
            call("DELETE", path, member, null));

    for (HttpResponse<String> r : responses) {
      assertThat(r.statusCode()).isEqualTo(403);
      assertThat(MAPPER.readTree(r.body()).get("code").asText()).isEqualTo("PERMISSION_DENIED");
    }
    JsonNode categories =
        MAPPER.readTree(call("GET", server, owner, null).body()).get("categories");
    assertThat(categories).hasSize(1);
    assertThat(categories.get(0).get("name").asText()).isEqualTo("Text Channels");
  }

  // Kịch bản: người ngoài server nhận 404 ở cả ba thao tác, kể cả PATCH và DELETE
  // Input: user lạ POST, PATCH, DELETE category của server có thật
  // Output: cả ba 404 NOT_FOUND và category không đổi
  @Test
  void nonMemberGets404() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    String server = createServer(owner);
    String category = firstCategory(server, owner);
    String path = server + "/categories/" + category;

    List<HttpResponse<String>> responses =
        List.of(
            call("POST", server + "/categories", stranger, "{\"name\":\"X\"}"),
            call("PATCH", path, stranger, "{\"name\":\"X\"}"),
            call("DELETE", path, stranger, null));

    for (HttpResponse<String> r : responses) {
      assertThat(r.statusCode()).isEqualTo(404);
      assertThat(MAPPER.readTree(r.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    }
    JsonNode categories =
        MAPPER.readTree(call("GET", server, owner, null).body()).get("categories");
    assertThat(categories).hasSize(1);
    assertThat(categories.get(0).get("name").asText()).isEqualTo("Text Channels");
  }

  // Kịch bản: tên category rỗng, toàn khoảng trắng, thiếu hoặc quá 100 ký tự bị từ chối
  // Input: owner POST với bốn dạng tên sai, và PATCH name "   " lên category có thật
  // Output: tất cả 400 VALIDATION_FAILED
  @Test
  void blankNameReturns400() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String category = firstCategory(server, owner);

    for (String body :
        new String[] {
          "{\"name\":\"\"}", "{\"name\":\"   \"}", "{}", "{\"name\":\"" + "a".repeat(101) + "\"}"
        }) {
      assertBadRequest(call("POST", server + "/categories", owner, body));
    }
    assertBadRequest(
        call("PATCH", server + "/categories/" + category, owner, "{\"name\":\"   \"}"));
    assertBadRequest(call("PATCH", server + "/categories/" + category, owner, "{\"position\":-1}"));
  }

  // Kịch bản: category của server khác hoặc không tồn tại trả 404, id không phải UUID trả 400
  // Input: PATCH và DELETE category của server B qua đường dẫn server A, UUID lạ, "not-a-uuid"
  // Output: 404 NOT_FOUND cho hai trường hợp đầu, 400 VALIDATION_FAILED cho id sai định dạng
  @Test
  void categoryOfOtherServerReturns404AndBadIdReturns400() throws Exception {
    UUID owner = UUID.randomUUID();
    String serverA = createServer(owner);
    String serverB = createServer(owner);
    String foreign = firstCategory(serverB, owner);

    for (String id : new String[] {foreign, UUID.randomUUID().toString()}) {
      String path = serverA + "/categories/" + id;
      assertThat(call("PATCH", path, owner, "{\"name\":\"X\"}").statusCode()).isEqualTo(404);
      assertThat(call("DELETE", path, owner, null).statusCode()).isEqualTo(404);
    }
    assertBadRequest(call("PATCH", serverA + "/categories/not-a-uuid", owner, "{\"name\":\"X\"}"));
    assertThat(MAPPER.readTree(call("GET", serverB, owner, null).body()).get("categories"))
        .hasSize(1);
  }

  // Kiểm tra phản hồi là 400 VALIDATION_FAILED
  private static void assertBadRequest(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(response.body()).get("code").asText())
        .isEqualTo("VALIDATION_FAILED");
  }

  // Tạo server qua REST và trả id
  private String createServer(UUID owner) throws Exception {
    HttpResponse<String> response = call("POST", "", owner, "{\"name\":\"Cat\"}");
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Lấy id category mặc định của server
  private String firstCategory(String server, UUID user) throws Exception {
    return MAPPER
        .readTree(call("GET", server, user, null).body())
        .get("categories")
        .get(0)
        .get("id")
        .asText();
  }

  // Thêm thành viên trực tiếp vào bảng members
  private void addMember(String serverId, UUID user) {
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at) values (?::uuid, ?, now())",
        serverId,
        user);
  }

  // Đọc payload outbox của category theo routing key, cũ trước mới sau
  private List<String> outbox(String routingKey, String categoryId) {
    return jdbc.queryForList(
        "select payload::text from community.outbox_event"
            + " where routing_key = ? and payload->>'categoryId' = ? order by created_at",
        String.class,
        routingKey,
        categoryId);
  }

  // Gọi method trên /api/v1/servers/{path} với Bearer của user, body null là không gửi body
  private HttpResponse<String> call(String method, String path, UUID user, String json)
      throws Exception {
    String suffix = path.isEmpty() ? "" : "/" + path;
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/servers" + suffix))
            .header("Authorization", bearer(user));
    if (json != null) {
      builder.header("Content-Type", "application/json");
    }
    return HttpClient.newHttpClient()
        .send(
            builder
                .method(
                    method,
                    json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
