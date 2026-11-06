package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra rời server và liệt kê thành viên theo con trỏ qua REST
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemberIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // Kịch bản: thành viên rời server thì mất quyền truy cập và có thể tham gia lại bằng lời mời
  // Input: member DELETE /servers/{id}/members/@me, GET server, rồi accept lời mời lại
  // Output: 204, outbox member.left một dòng, GET server 404, accept 200 và lại là member
  @Test
  void memberLeavesAndLosesAccess() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String server = createServer(owner);
    String invite = createInvite(server, owner);
    assertThat(
            call("POST", "/api/v1/invites/" + invite + "/accept", bearer(member), null)
                .statusCode())
        .isEqualTo(200);

    HttpResponse<String> left = call("DELETE", membersOf(server) + "/@me", bearer(member), null);

    assertThat(left.statusCode()).isEqualTo(204);
    assertThat(isMember(server, member)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from community.outbox_event where routing_key = 'member.left'"
                    + " and aggregate_id = ?::uuid and payload->>'userId' = ?",
                Long.class,
                server,
                member.toString()))
        .isEqualTo(1);
    assertThat(call("GET", "/api/v1/servers/" + server, bearer(member), null).statusCode())
        .isEqualTo(404);
    assertThat(
            call("POST", "/api/v1/invites/" + invite + "/accept", bearer(member), null)
                .statusCode())
        .isEqualTo(200);
    assertThat(isMember(server, member)).isTrue();
  }

  // Kịch bản: chủ server không được rời server
  // Input: owner DELETE /servers/{id}/members/@me
  // Output: 403 PERMISSION_DENIED, detail nói phải xóa server, owner vẫn là member
  @Test
  void ownerCannotLeave403() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);

    HttpResponse<String> response = call("DELETE", membersOf(server) + "/@me", bearer(owner), null);

    assertThat(response.statusCode()).isEqualTo(403);
    JsonNode problem = MAPPER.readTree(response.body());
    assertThat(problem.get("code").asText()).isEqualTo("PERMISSION_DENIED");
    assertThat(problem.get("detail").asText()).contains("delete the server");
    assertThat(isMember(server, owner)).isTrue();
  }

  // Kịch bản: người ngoài server hoặc server không tồn tại thì rời bị 404
  // Input: user lạ DELETE /servers/{id}/members/@me với server thật và server không có
  // Output: cả hai 404 NOT_FOUND, không có outbox member.left
  @Test
  void nonMemberLeave404() throws Exception {
    String server = createServer(UUID.randomUUID());
    UUID stranger = UUID.randomUUID();

    for (String id : new String[] {server, UUID.randomUUID().toString()}) {
      HttpResponse<String> response =
          call("DELETE", membersOf(id) + "/@me", bearer(stranger), null);
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(MAPPER.readTree(response.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from community.outbox_event where routing_key = 'member.left'"
                    + " and aggregate_id = ?::uuid",
                Long.class,
                server))
        .isZero();
  }

  // Kịch bản: duyệt hết 25 thành viên bằng con trỏ, mỗi người đúng một lần
  // Input: server 25 member, GET /members?limit=10 theo nextCursor đến hết
  // Output: các trang 10/10/5, 25 userId khác nhau khớp DB, nextCursor trang cuối null
  @Test
  void paginationCoversAllMembersOnce() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at)"
            + " select ?::uuid, gen_random_uuid(), now() from generate_series(1, 24)",
        server);
    Set<String> seen = new HashSet<>();
    List<Integer> sizes = new java.util.ArrayList<>();
    String cursor = null;

    do {
      String path = membersOf(server) + "?limit=10" + (cursor == null ? "" : "&cursor=" + cursor);
      HttpResponse<String> response = call("GET", path, bearer(owner), null);
      assertThat(response.statusCode()).isEqualTo(200);
      JsonNode page = MAPPER.readTree(response.body());
      sizes.add(page.get("items").size());
      for (JsonNode item : page.get("items")) {
        assertThat(seen.add(item.get("userId").asText())).isTrue();
        assertThat(item.get("joinedAt").asText()).isNotBlank();
        assertThat(item.get("roleIds")).isEmpty();
      }
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
    } while (cursor != null);

    assertThat(sizes).containsExactly(10, 10, 5);
    assertThat(seen)
        .containsExactlyInAnyOrderElementsOf(
            jdbc.queryForList(
                "select user_id::text from community.members where server_id = ?::uuid",
                String.class,
                server));
  }

  // Kịch bản: nextCursor là base64url không padding của userId cuối trang, mặc định limit 50
  // Input: server 3 member, GET ?limit=2 rồi GET không limit
  // Output: nextCursor giải mã ra userId cuối trang 1; không limit thì 3 item và nextCursor null
  @Test
  void cursorEncodesLastUserIdAndDefaultLimitApplies() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at)"
            + " select ?::uuid, gen_random_uuid(), now() from generate_series(1, 2)",
        server);

    JsonNode first =
        MAPPER.readTree(call("GET", membersOf(server) + "?limit=2", bearer(owner), null).body());
    JsonNode all = MAPPER.readTree(call("GET", membersOf(server), bearer(owner), null).body());

    String cursor = first.get("nextCursor").asText();
    assertThat(cursor).doesNotContain("=");
    assertThat(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8))
        .isEqualTo(first.get("items").get(1).get("userId").asText());
    assertThat(all.get("items")).hasSize(3);
    assertThat(all.get("nextCursor").isNull()).isTrue();
  }

  // Kịch bản: limit ngoài 1..100 hoặc không phải số bị từ chối
  // Input: GET /members với limit 0, 101, -5, abc
  // Output: tất cả 400; limit 100 thì 200
  @Test
  void limit0And101Return400() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);

    for (String limit : new String[] {"0", "101", "-5", "abc"}) {
      HttpResponse<String> response =
          call("GET", membersOf(server) + "?limit=" + limit, bearer(owner), null);
      assertThat(response.statusCode()).as(limit).isEqualTo(400);
    }
    assertThat(call("GET", membersOf(server) + "?limit=100", bearer(owner), null).statusCode())
        .isEqualTo(200);
  }

  // Kịch bản: con trỏ sai định dạng bị từ chối
  // Input: GET /members với cursor "!!!", base64url của chữ không phải UUID, và rỗng
  // Output: cả ba 400 VALIDATION_FAILED
  @Test
  void garbageCursorReturns400() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String notUuid =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("hello".getBytes(StandardCharsets.UTF_8));

    for (String cursor : new String[] {"!!!", notUuid, ""}) {
      HttpResponse<String> response =
          call("GET", membersOf(server) + "?cursor=" + cursor, bearer(owner), null);
      assertThat(response.statusCode()).as(cursor).isEqualTo(400);
      assertThat(MAPPER.readTree(response.body()).get("code").asText())
          .isEqualTo("VALIDATION_FAILED");
    }
  }

  // Kịch bản: người ngoài server hoặc server không tồn tại không xem được danh sách
  // Input: user lạ GET /servers/{id}/members với server thật và server không có
  // Output: cả hai 404 NOT_FOUND
  @Test
  void nonMemberListGets404() throws Exception {
    String server = createServer(UUID.randomUUID());
    UUID stranger = UUID.randomUUID();

    for (String id : new String[] {server, UUID.randomUUID().toString()}) {
      HttpResponse<String> response = call("GET", membersOf(id), bearer(stranger), null);
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(MAPPER.readTree(response.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    }
  }

  // Đường dẫn thành viên của server
  private static String membersOf(String server) {
    return "/api/v1/servers/" + server + "/members";
  }

  // Tạo server tên "Mem" qua REST và trả id
  private String createServer(UUID owner) throws Exception {
    HttpResponse<String> response =
        call("POST", "/api/v1/servers", bearer(owner), "{\"name\":\"Mem\"}");
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Tạo lời mời mặc định qua REST và trả mã
  private String createInvite(String server, UUID user) throws Exception {
    HttpResponse<String> response =
        call("POST", "/api/v1/servers/" + server + "/invites", bearer(user), "{}");
    return MAPPER.readTree(response.body()).get("code").asText();
  }

  // Cho biết user có là thành viên của server không
  private boolean isMember(String server, UUID user) {
    return jdbc.queryForObject(
            "select count(*) from community.members where server_id = ?::uuid and user_id = ?",
            Long.class,
            server,
            user)
        == 1;
  }

  // Gọi method tới path với header Authorization tùy chọn, body null là không gửi body
  private HttpResponse<String> call(String method, String path, String auth, String json)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (auth != null) {
      builder.header("Authorization", auth);
    }
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
