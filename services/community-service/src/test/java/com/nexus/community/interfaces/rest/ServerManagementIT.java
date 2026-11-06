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

// Kiểm tra GET, PATCH, DELETE /api/v1/servers/{id} với phân quyền thành viên và chủ server
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerManagementIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // Kịch bản: thành viên xem được chi tiết server như lúc tạo
  // Input: owner tạo server, thêm user B làm thành viên, B GET /api/v1/servers/{id}
  // Output: 200 với tên, 1 category, 1 kênh general và role @everyone
  @Test
  void memberCanGetDetail() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String id = createServer(owner, "Detail");
    addMember(id, member);

    HttpResponse<String> response = call("GET", id, member, null);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.get("id").asText()).isEqualTo(id);
    assertThat(body.get("name").asText()).isEqualTo("Detail");
    assertThat(body.get("ownerId").asText()).isEqualTo(owner.toString());
    assertThat(body.get("categories")).hasSize(1);
    assertThat(body.get("channels")).hasSize(1);
    assertThat(body.get("channels").get(0).get("name").asText()).isEqualTo("general");
    assertThat(body.get("roles")).hasSize(1);
    assertThat(body.get("roles").get(0).get("name").asText()).isEqualTo("@everyone");
  }

  // Kịch bản: người ngoài và server không tồn tại đều trả cùng một 404
  // Input: user lạ GET server có thật, rồi GET một UUID không tồn tại
  // Output: cả hai 404 NOT_FOUND với cùng detail, không lộ sự tồn tại
  @Test
  void nonMemberGetsDetail404() throws Exception {
    String id = createServer(UUID.randomUUID(), "Private");
    UUID stranger = UUID.randomUUID();

    HttpResponse<String> real = call("GET", id, stranger, null);
    HttpResponse<String> unknown = call("GET", UUID.randomUUID().toString(), stranger, null);

    assertThat(real.statusCode()).isEqualTo(404);
    assertThat(unknown.statusCode()).isEqualTo(404);
    JsonNode a = MAPPER.readTree(real.body());
    JsonNode b = MAPPER.readTree(unknown.body());
    assertThat(a.get("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(b.get("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(a.get("detail")).isEqualTo(b.get("detail"));
  }

  // Kịch bản: serverId không phải UUID bị từ chối ở biên REST
  // Input: GET /api/v1/servers/not-a-uuid
  // Output: 400 VALIDATION_FAILED
  @Test
  void nonUuidServerIdReturns400() throws Exception {
    HttpResponse<String> response = call("GET", "not-a-uuid", UUID.randomUUID(), null);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(response.body()).get("code").asText())
        .isEqualTo("VALIDATION_FAILED");
  }

  // Kịch bản: chủ server đổi tên và outbox nhận server.updated
  // Input: owner PATCH {"name":"  Renamed  "} rồi GET lại
  // Output: 200 tên "Renamed", GET thấy tên mới, outbox server.updated payload name "Renamed"
  @Test
  void ownerRenames() throws Exception {
    UUID owner = UUID.randomUUID();
    String id = createServer(owner, "Before");

    HttpResponse<String> response = call("PATCH", id, owner, "{\"name\":\"  Renamed  \"}");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(response.body()).get("name").asText()).isEqualTo("Renamed");
    assertThat(MAPPER.readTree(call("GET", id, owner, null).body()).get("name").asText())
        .isEqualTo("Renamed");
    List<Map<String, Object>> rows = outbox(id, "server.updated");
    assertThat(rows).hasSize(1);
    assertThat(MAPPER.readTree((String) rows.get(0).get("payload")).get("name").asText())
        .isEqualTo("Renamed");
  }

  // Kịch bản: thành viên không phải chủ không được đổi tên
  // Input: user B là thành viên PATCH tên server của owner
  // Output: 403 PERMISSION_DENIED và tên server không đổi
  @Test
  void memberNonOwnerRenameReturns403() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String id = createServer(owner, "Keep");
    addMember(id, member);

    HttpResponse<String> response = call("PATCH", id, member, "{\"name\":\"Hijack\"}");

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(MAPPER.readTree(response.body()).get("code").asText())
        .isEqualTo("PERMISSION_DENIED");
    assertThat(MAPPER.readTree(call("GET", id, owner, null).body()).get("name").asText())
        .isEqualTo("Keep");
  }

  // Kịch bản: tên rỗng, toàn khoảng trắng hoặc thiếu bị từ chối khi đổi tên
  // Input: owner PATCH với name rỗng, name toàn khoảng trắng và body trống
  // Output: cả ba 400 VALIDATION_FAILED
  @Test
  void blankRenameReturns400() throws Exception {
    UUID owner = UUID.randomUUID();
    String id = createServer(owner, "Name");

    for (String body : new String[] {"{\"name\":\"\"}", "{\"name\":\"   \"}", "{}"}) {
      HttpResponse<String> response = call("PATCH", id, owner, body);

      assertThat(response.statusCode()).as(body).isEqualTo(400);
      assertThat(MAPPER.readTree(response.body()).get("code").asText())
          .isEqualTo("VALIDATION_FAILED");
    }
  }

  // Kịch bản: chủ server xóa thì toàn bộ dữ liệu con biến mất và phát server.deleted
  // Input: owner DELETE server rồi GET lại và đếm các bảng con
  // Output: 204, GET 404, roles/categories/channels/members rỗng, outbox server.deleted có serverId
  @Test
  void ownerDeletesAndEverythingIsGone() throws Exception {
    UUID owner = UUID.randomUUID();
    String id = createServer(owner, "Doomed");

    HttpResponse<String> response = call("DELETE", id, owner, null);

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(call("GET", id, owner, null).statusCode()).isEqualTo(404);
    for (String table : new String[] {"servers", "roles", "categories", "channels", "members"}) {
      String column = table.equals("servers") ? "id" : "server_id";
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from community." + table + " where " + column + " = ?::uuid",
                  Long.class,
                  id))
          .as(table)
          .isZero();
    }
    List<Map<String, Object>> rows = outbox(id, "server.deleted");
    assertThat(rows).hasSize(1);
    assertThat(MAPPER.readTree((String) rows.get(0).get("payload")).get("serverId").asText())
        .isEqualTo(id);
  }

  // Kịch bản: thành viên không phải chủ không xóa được server
  // Input: user B là thành viên DELETE server của owner
  // Output: 403 PERMISSION_DENIED và owner vẫn GET được 200
  @Test
  void nonOwnerDeleteReturns403() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String id = createServer(owner, "Safe");
    addMember(id, member);

    HttpResponse<String> response = call("DELETE", id, member, null);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(MAPPER.readTree(response.body()).get("code").asText())
        .isEqualTo("PERMISSION_DENIED");
    assertThat(call("GET", id, owner, null).statusCode()).isEqualTo(200);
  }

  // Tạo server qua REST và trả id
  private String createServer(UUID owner, String name) throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(url("")))
                .header("Authorization", bearer(owner))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name + "\"}")));
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Thêm thành viên trực tiếp vào bảng members
  private void addMember(String serverId, UUID user) {
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at) values (?::uuid, ?, now())",
        serverId,
        user);
  }

  // Đọc các dòng outbox của server theo routing key
  private List<Map<String, Object>> outbox(String serverId, String routingKey) {
    return jdbc.queryForList(
        "select payload::text as payload from community.outbox_event"
            + " where routing_key = ? and payload->>'serverId' = ?",
        routingKey,
        serverId);
  }

  // Địa chỉ endpoint server cộng hậu tố đường dẫn
  private String url(String suffix) {
    return "http://localhost:" + port + "/api/v1/servers" + suffix;
  }

  // Gọi method trên /servers/{id} với Bearer của user, body null nghĩa là không gửi body
  private HttpResponse<String> call(String method, String id, UUID user, String json)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url("/" + id))).header("Authorization", bearer(user));
    if (json != null) {
      builder.header("Content-Type", "application/json");
    }
    return send(
        builder.method(
            method,
            json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json)));
  }

  // Gửi request và đọc body dạng chuỗi
  private static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
