package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
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

// Kiểm tra POST và GET /api/v1/servers với JWT thật từ JWKS giả và Postgres thật
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerControllerIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // Kịch bản: tạo server nhận 201 kèm Location và mặc định role, category, kênh
  // Input: POST /api/v1/servers {"name":"Nexus HQ"} với Bearer của một user
  // Output: 201, Location đúng, 1 role @everyone, category Text Channels, kênh general
  @Test
  void createServerReturns201WithDefaults() throws Exception {
    UUID user = UUID.randomUUID();

    HttpResponse<String> response = post(user, "{\"name\":\"Nexus HQ\"}");

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode body = MAPPER.readTree(response.body());
    String id = body.get("id").asText();
    assertThat(response.headers().firstValue("Location")).contains("/api/v1/servers/" + id);
    assertThat(body.get("name").asText()).isEqualTo("Nexus HQ");
    assertThat(body.get("ownerId").asText()).isEqualTo(user.toString());
    assertThat(body.get("roles")).hasSize(1);
    assertThat(body.get("roles").get(0).get("name").asText()).isEqualTo("@everyone");
    assertThat(body.get("roles").get(0).get("isDefault").asBoolean()).isTrue();
    assertThat(body.get("categories")).hasSize(1);
    JsonNode category = body.get("categories").get(0);
    assertThat(category.get("name").asText()).isEqualTo("Text Channels");
    assertThat(body.get("channels")).hasSize(1);
    JsonNode channel = body.get("channels").get(0);
    assertThat(channel.get("name").asText()).isEqualTo("general");
    assertThat(channel.get("categoryId").asText()).isEqualTo(category.get("id").asText());
  }

  // Kịch bản: tạo server ghi đúng bốn dòng outbox theo thứ tự
  // Input: POST /api/v1/servers hợp lệ rồi đọc outbox_event của server đó
  // Output: 4 dòng server.created, category.created, channel.created, member.joined, payload JSON
  @Test
  void createServerWritesFourOutboxRows() throws Exception {
    UUID user = UUID.randomUUID();
    String id = MAPPER.readTree(post(user, "{\"name\":\"Outbox\"}").body()).get("id").asText();

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select routing_key, aggregate_type, payload::text as payload from"
                + " community.outbox_event where payload->>'serverId' = ? order by id",
            id);

    assertThat(rows)
        .extracting(r -> r.get("routing_key"))
        .containsExactly("server.created", "category.created", "channel.created", "member.joined");
    assertThat(rows)
        .extracting(r -> r.get("aggregate_type"))
        .containsExactly("Server", "Category", "Channel", "Member");
    JsonNode created = MAPPER.readTree((String) rows.get(0).get("payload"));
    assertThat(created.get("name").asText()).isEqualTo("Outbox");
    assertThat(created.get("ownerId").asText()).isEqualTo(user.toString());
    JsonNode joined = MAPPER.readTree((String) rows.get(3).get("payload"));
    assertThat(joined.get("userId").asText()).isEqualTo(user.toString());
    assertThat(joined.get("joinedAt").asText()).isNotBlank();
  }

  // Kịch bản: tên rỗng hoặc toàn khoảng trắng bị từ chối
  // Input: POST với name rỗng, name toàn khoảng trắng và thiếu name
  // Output: cả ba 400 VALIDATION_FAILED
  @Test
  void blankNameReturns400() throws Exception {
    UUID user = UUID.randomUUID();

    for (String body : new String[] {"{\"name\":\"\"}", "{\"name\":\"   \"}", "{}"}) {
      HttpResponse<String> response = post(user, body);

      assertThat(response.statusCode()).as(body).isEqualTo(400);
      assertThat(MAPPER.readTree(response.body()).get("code").asText())
          .isEqualTo("VALIDATION_FAILED");
    }
  }

  // Kịch bản: danh sách chỉ gồm server của chính người gọi
  // Input: user A tạo một server, user B tạo một server, mỗi bên GET /api/v1/servers
  // Output: A chỉ thấy server của A, B chỉ thấy server của B
  @Test
  void listReturnsOnlyMyServers() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    String idA = MAPPER.readTree(post(a, "{\"name\":\"A\"}").body()).get("id").asText();
    String idB = MAPPER.readTree(post(b, "{\"name\":\"B\"}").body()).get("id").asText();

    JsonNode listA = MAPPER.readTree(get(bearerHeader(a)).body());
    JsonNode listB = MAPPER.readTree(get(bearerHeader(b)).body());

    assertThat(listA).hasSize(1);
    assertThat(listA.get(0).get("id").asText()).isEqualTo(idA);
    assertThat(listA.get(0).get("ownerId").asText()).isEqualTo(a.toString());
    assertThat(listB).hasSize(1);
    assertThat(listB.get(0).get("id").asText()).isEqualTo(idB);
  }

  // Kịch bản: danh sách và tạo server đều cần Bearer
  // Input: GET và POST /api/v1/servers không có Authorization
  // Output: cả hai 401 UNAUTHENTICATED
  @Test
  void listRequiresBearer() throws Exception {
    HttpResponse<String> list = get(null);
    HttpResponse<String> create =
        send(
            HttpRequest.newBuilder(URI.create(url()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")));

    assertThat(list.statusCode()).isEqualTo(401);
    assertThat(MAPPER.readTree(list.body()).get("code").asText()).isEqualTo("UNAUTHENTICATED");
    assertThat(create.statusCode()).isEqualTo(401);
  }

  // Lấy giá trị header Authorization Bearer cho user
  private static String bearerHeader(UUID user) {
    return bearer(user);
  }

  // Địa chỉ gốc của endpoint server
  private String url() {
    return "http://localhost:" + port + "/api/v1/servers";
  }

  // Gọi POST tạo server với Bearer của user
  private HttpResponse<String> post(UUID user, String json) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(url()))
            .header("Authorization", bearer(user))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)));
  }

  // Gọi GET danh sách server, authorization null nghĩa là không gửi header
  private HttpResponse<String> get(String authorization) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url()));
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return send(builder);
  }

  // Gửi request và đọc body dạng chuỗi
  private static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
