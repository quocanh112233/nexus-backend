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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Lớp cha cho IT kick và ban: dựng server có phân cấp role và các hàm gọi REST dùng chung
abstract class ModerationTestBase extends CommunityIntegrationTest {

  protected static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final long KICK_AND_BAN = 2048 | 4096;
  private static final long ADMINISTRATOR = 1;

  @Autowired protected JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // Dữ liệu dựng sẵn: owner, Mod và Mod2 giữ role Mod (1), Admin giữ role Admin (3), member thường
  protected static final class Fx {
    final UUID owner;
    final UUID mod;
    final UUID mod2;
    final UUID admin;
    final UUID plain;
    final String server;
    final String general;

    // Gom các định danh đã dựng
    Fx(UUID owner, UUID mod, UUID mod2, UUID admin, UUID plain, String server, String general) {
      this.owner = owner;
      this.mod = mod;
      this.mod2 = mod2;
      this.admin = admin;
      this.plain = plain;
      this.server = server;
      this.general = general;
    }
  }

  // Dựng server "Moderation" với role Mod (KICK và BAN, vị trí 1), Admin (vị trí 3) và các user
  protected Fx fx() throws Exception {
    UUID owner = UUID.randomUUID();
    JsonNode created = MAPPER.readTree(call("POST", "", owner, "{\"name\":\"Moderation\"}").body());
    String server = created.get("id").asText();
    String general = created.get("channels").get(0).get("id").asText();
    String modRole =
        role(
            server,
            owner,
            "{\"name\":\"Mod\",\"permissions\":" + KICK_AND_BAN + ",\"position\":1}");
    role(server, owner, "{\"name\":\"Mid\",\"position\":2}");
    String adminRole =
        role(
            server,
            owner,
            "{\"name\":\"Admin\",\"permissions\":" + ADMINISTRATOR + ",\"position\":3}");
    Fx f =
        new Fx(
            owner,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            server,
            general);
    for (UUID u : List.of(f.mod, f.mod2, f.admin, f.plain)) {
      addMember(server, u);
    }
    assign(server, f.mod, modRole);
    assign(server, f.mod2, modRole);
    assign(server, f.admin, adminRole);
    return f;
  }

  // Tạo role qua REST, kiểm tra 201 và trả id
  private String role(String server, UUID owner, String json) throws Exception {
    HttpResponse<String> response = call("POST", server + "/roles", owner, json);
    assertThat(response.statusCode()).isEqualTo(201);
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Owner cộng thêm các bit quyền vào role @everyone qua PATCH, giữ nguyên quyền cũ
  protected void grantEveryone(Fx f, long bits) throws Exception {
    JsonNode everyone =
        MAPPER.readTree(call("GET", f.server + "/roles", f.owner, null).body()).get(0);
    long permissions = everyone.get("permissions").asLong() | bits;
    HttpResponse<String> response =
        call(
            "PATCH",
            f.server + "/roles/" + everyone.get("id").asText(),
            f.owner,
            "{\"permissions\":" + permissions + "}");
    assertThat(response.statusCode()).isEqualTo(200);
  }

  // Id role theo tên trong server, role không có thì thất bại
  protected String roleId(Fx f, String name) throws Exception {
    for (JsonNode r : MAPPER.readTree(call("GET", f.server + "/roles", f.owner, null).body())) {
      if (r.get("name").asText().equals(name)) {
        return r.get("id").asText();
      }
    }
    throw new AssertionError("role not found " + name);
  }

  // Thêm thành viên trực tiếp vào bảng members
  protected void addMember(String server, UUID user) {
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at) values (?::uuid, ?, now())",
        server,
        user);
  }

  // Gán role cho thành viên trực tiếp vào bảng member_roles
  private void assign(String server, UUID user, String role) {
    jdbc.update(
        "insert into community.member_roles (server_id, user_id, role_id)"
            + " values (?::uuid, ?, ?::uuid)",
        server,
        user,
        role);
  }

  // Owner tạo lời mời mặc định và trả mã
  protected String invite(Fx f) throws Exception {
    return MAPPER
        .readTree(call("POST", f.server + "/invites", f.owner, null).body())
        .get("code")
        .asText();
  }

  // User tham gia server bằng mã lời mời
  protected HttpResponse<String> accept(String code, UUID user) throws Exception {
    return callAbsolute("POST", "/api/v1/invites/" + code + "/accept", user, null);
  }

  // Các userId trong trang đầu của GET members do owner xem
  protected List<String> memberIds(Fx f) throws Exception {
    List<String> ids = new ArrayList<>();
    MAPPER
        .readTree(call("GET", f.server + "/members?limit=100", f.owner, null).body())
        .get("items")
        .forEach(m -> ids.add(m.get("userId").asText()));
    return ids;
  }

  // Payload outbox theo routing key của server, cũ trước mới sau
  protected List<JsonNode> outbox(Fx f, String routingKey) {
    return jdbc
        .queryForList(
            "select payload::text from community.outbox_event where routing_key = ?"
                + " and aggregate_id = ?::uuid order by created_at",
            String.class,
            routingKey,
            f.server)
        .stream()
        .map(ModerationTestBase::parse)
        .toList();
  }

  // Phân tích JSON, lỗi thì ném không kiểm tra
  private static JsonNode parse(String json) {
    return MAPPER.readTree(json);
  }

  // Số overwrite MEMBER của user trong server
  protected int memberOverwrites(Fx f, UUID user) {
    return jdbc.queryForObject(
        "select count(*) from community.channel_permission_overwrites o"
            + " join community.channels c on c.id = o.channel_id"
            + " where c.server_id = ?::uuid and o.target_id = ?",
        Integer.class,
        f.server,
        user);
  }

  // Owner đặt overwrite MEMBER deny SEND_MESSAGES cho user ở kênh general
  protected void overwriteFor(Fx f, UUID user) throws Exception {
    HttpResponse<String> response =
        call(
            "PUT",
            f.server + "/channels/" + f.general + "/permissions/member/" + user,
            f.owner,
            "{\"allow\":0,\"deny\":64}");
    assertThat(response.statusCode()).isEqualTo(204);
  }

  // Đọc trường code của phản hồi lỗi
  protected static String code(HttpResponse<String> response) throws Exception {
    return MAPPER.readTree(response.body()).get("code").asText();
  }

  // Gọi method trên /api/v1/servers/{path} với Bearer của user, body null là không gửi body
  protected HttpResponse<String> call(String method, String path, UUID user, String json)
      throws Exception {
    return callAbsolute(method, "/api/v1/servers" + (path.isEmpty() ? "" : "/" + path), user, json);
  }

  // Gọi method trên đường dẫn tuyệt đối của app với Bearer của user
  private HttpResponse<String> callAbsolute(String method, String path, UUID user, String json)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
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
