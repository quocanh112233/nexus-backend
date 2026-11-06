package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra CRUD role qua REST: phân quyền, thứ bậc, đầu vào, outbox và xung đột phiên bản
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoleIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final long MANAGE_ROLES = 4;
  private static final long KICK_MEMBERS = 2048;
  private static final long BAN_MEMBERS = 4096;

  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private ServerRepository serverRepository;
  @LocalServerPort private int port;

  // Dữ liệu dựng sẵn: owner, Lead giữ role Lead ở vị trí 2, và ba role Low 1, Lead 2, High 3
  private record Hierarchy(UUID owner, UUID lead, String server, String low, String high) {}

  // Kịch bản: owner tạo role Mod và outbox ghi role.created với permissions dạng chuỗi
  // Input: owner POST {name:"Mod", color:255, permissions:KICK|BAN} rồi GET danh sách
  // Output: 201 kèm Location, đủ trường, 2 role, outbox role.created permissions "6144"
  @Test
  void ownerCreatesRole() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);

    HttpResponse<String> response =
        call(
            "POST",
            server + "/roles",
            owner,
            "{\"name\":\"Mod\",\"color\":255,\"permissions\":"
                + (KICK_MEMBERS | BAN_MEMBERS)
                + "}");

    JsonNode role = MAPPER.readTree(response.body());
    String id = role.get("id").asText();
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.headers().firstValue("Location"))
        .contains("/api/v1/servers/" + server + "/roles/" + id);
    assertThat(role.get("name").asText()).isEqualTo("Mod");
    assertThat(role.get("color").asInt()).isEqualTo(255);
    assertThat(role.get("permissions").asLong()).isEqualTo(KICK_MEMBERS | BAN_MEMBERS);
    assertThat(role.get("position").asInt()).isEqualTo(1);
    assertThat(role.get("isDefault").asBoolean()).isFalse();
    assertThat(roles(server, owner)).hasSize(2);
    List<String> created = outbox("role.created", id);
    assertThat(created).hasSize(1);
    assertThat(MAPPER.readTree(created.get(0)).get("permissions").asText()).isEqualTo("6144");
  }

  // Kịch bản: thiếu color, permissions, position thì dùng mặc định 0, 0 và vị trí 1
  // Input: owner POST chỉ {name:"Plain"} sau khi đã có một role
  // Output: 201 color 0, permissions 0, role mới ở vị trí 1 và đẩy role cũ lên 2
  @Test
  void createDefaultsAndShiftsRolesUp() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String first = createRole(server, owner, "{\"name\":\"First\"}").get("id").asText();

    JsonNode plain = createRole(server, owner, "{\"name\":\"Plain\"}");

    assertThat(plain.get("color").asInt()).isZero();
    assertThat(plain.get("permissions").asLong()).isZero();
    assertThat(plain.get("position").asInt()).isEqualTo(1);
    assertThat(roleOf(server, owner, first).get("position").asInt()).isEqualTo(2);
  }

  // Kịch bản: mọi thành viên liệt kê được role theo vị trí tăng, người ngoài nhận 404
  // Input: thành viên thường và người lạ GET /roles khi server có hai role tự tạo
  // Output: thành viên 200 vị trí 0,1,2 tăng dần và có @everyone isDefault; người lạ 404
  @Test
  void membersListRolesInOrder() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String server = createServer(owner);
    addMember(server, member);
    createRole(server, owner, "{\"name\":\"A\"}");
    createRole(server, owner, "{\"name\":\"B\"}");

    JsonNode list = MAPPER.readTree(call("GET", server + "/roles", member, null).body());

    assertThat(list).hasSize(3);
    assertThat(list.get(0).get("position").asInt()).isZero();
    assertThat(list.get(0).get("isDefault").asBoolean()).isTrue();
    assertThat(list.get(0).get("name").asText()).isEqualTo("@everyone");
    assertThat(list.get(1).get("position").asInt()).isEqualTo(1);
    assertThat(list.get(2).get("position").asInt()).isEqualTo(2);
    assertThat(call("GET", server + "/roles", UUID.randomUUID(), null).statusCode()).isEqualTo(404);
  }

  // Kịch bản: thành viên thường 403 và người ngoài 404 ở cả POST, PATCH, DELETE
  // Input: user thường và user lạ gọi POST, PATCH, DELETE role của owner
  // Output: thành viên 403 PERMISSION_DENIED, người ngoài 404 NOT_FOUND, role không đổi
  @Test
  void membersWithoutPermissionAndStrangersAreRejected() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    String server = createServer(owner);
    addMember(server, member);
    String role = createRole(server, owner, "{\"name\":\"Mod\"}").get("id").asText();

    for (UUID user : new UUID[] {member, stranger}) {
      int expected = user.equals(member) ? 403 : 404;
      HttpResponse<String> post = call("POST", server + "/roles", user, "{\"name\":\"X\"}");
      assertThat(post.statusCode()).isEqualTo(expected);
      assertThat(call("PATCH", server + "/roles/" + role, user, "{\"name\":\"X\"}").statusCode())
          .isEqualTo(expected);
      assertThat(call("DELETE", server + "/roles/" + role, user, null).statusCode())
          .isEqualTo(expected);
      if (user.equals(member)) {
        assertThat(MAPPER.readTree(post.body()).get("code").asText())
            .isEqualTo("PERMISSION_DENIED");
      }
    }
    assertThat(roleOf(server, owner, role).get("name").asText()).isEqualTo("Mod");
  }

  // Kịch bản: Lead (vị trí 2, MANAGE_ROLES) chỉ sửa và xóa được role thấp hơn mình
  // Input: Lead PATCH và DELETE role Lead (2), High (3), rồi PATCH Low (1) và DELETE Low
  // Output: role vị trí >= 2 trả 403 và không đổi; Low sửa được 200 rồi xóa được 204
  @Test
  void leadManagesOnlyLowerRoles() throws Exception {
    Hierarchy h = hierarchy();
    String lead = roles(h.server, h.owner).get(2).get("id").asText();

    for (String role : new String[] {lead, h.high}) {
      assertThat(
              call("PATCH", h.server + "/roles/" + role, h.lead, "{\"name\":\"Hack\"}")
                  .statusCode())
          .isEqualTo(403);
      assertThat(call("DELETE", h.server + "/roles/" + role, h.lead, null).statusCode())
          .isEqualTo(403);
      assertThat(roleOf(h.server, h.owner, role).get("name").asText()).isNotEqualTo("Hack");
    }
    assertThat(
            call("PATCH", h.server + "/roles/" + h.low, h.lead, "{\"name\":\"Low2\"}").statusCode())
        .isEqualTo(200);
    assertThat(roleOf(h.server, h.owner, h.low).get("name").asText()).isEqualTo("Low2");
    assertThat(call("DELETE", h.server + "/roles/" + h.low, h.lead, null).statusCode())
        .isEqualTo(204);
    assertThat(roles(h.server, h.owner)).hasSize(3);
  }

  // Kịch bản: không cấp hoặc gỡ được bit quyền mà chính mình không có
  // Input: Lead (chỉ MANAGE_ROLES) POST role KICK, PATCH Low thêm KICK, rồi gỡ bit KICK khỏi Low
  // Output: ba lần đều 403; PATCH Low thêm MANAGE_ROLES (bit Lead có) thì 200
  @Test
  void cannotGrantBitsOutsideOwnPermissions() throws Exception {
    Hierarchy h = hierarchy();

    assertThat(
            call(
                    "POST",
                    h.server + "/roles",
                    h.lead,
                    "{\"name\":\"K\",\"permissions\":" + KICK_MEMBERS + "}")
                .statusCode())
        .isEqualTo(403);
    assertThat(
            call(
                    "PATCH",
                    h.server + "/roles/" + h.low,
                    h.lead,
                    "{\"permissions\":" + KICK_MEMBERS + "}")
                .statusCode())
        .isEqualTo(403);
    call(
        "PATCH",
        h.server + "/roles/" + h.low,
        h.owner,
        "{\"permissions\":" + (KICK_MEMBERS | MANAGE_ROLES) + "}");
    assertThat(
            call(
                    "PATCH",
                    h.server + "/roles/" + h.low,
                    h.lead,
                    "{\"permissions\":" + MANAGE_ROLES + "}")
                .statusCode())
        .isEqualTo(403);
    call("PATCH", h.server + "/roles/" + h.low, h.owner, "{\"permissions\":0}");
    assertThat(
            call(
                    "PATCH",
                    h.server + "/roles/" + h.low,
                    h.lead,
                    "{\"permissions\":" + MANAGE_ROLES + "}")
                .statusCode())
        .isEqualTo(200);
    assertThat(roleOf(h.server, h.owner, h.low).get("permissions").asLong())
        .isEqualTo(MANAGE_ROLES);
  }

  // Kịch bản: không đặt được role lên vị trí ngang hoặc cao hơn role cao nhất của mình
  // Input: Lead (vị trí 2) POST role vị trí 3, PATCH Low sang vị trí 2, rồi POST role vị trí 2
  // Output: hai lần đầu 403; POST vị trí 2 (role mới được ngang cấp) 201
  @Test
  void cannotPlaceRoleAtOrAboveOwnHighest() throws Exception {
    Hierarchy h = hierarchy();

    assertThat(
            call("POST", h.server + "/roles", h.lead, "{\"name\":\"Up\",\"position\":3}")
                .statusCode())
        .isEqualTo(403);
    assertThat(call("PATCH", h.server + "/roles/" + h.low, h.lead, "{\"position\":2}").statusCode())
        .isEqualTo(403);
    assertThat(roleOf(h.server, h.owner, h.low).get("position").asInt()).isEqualTo(1);
    assertThat(
            call("POST", h.server + "/roles", h.lead, "{\"name\":\"Peer\",\"position\":2}")
                .statusCode())
        .isEqualTo(201);
  }

  // Kịch bản: @everyone cố định tên và vị trí nhưng đổi được quyền, không xóa được
  // Input: owner PATCH @everyone name, rồi position, rồi permissions=KICK, rồi DELETE
  // Output: 400, 400, 200 (outbox role.updated), 400 VALIDATION_FAILED
  @Test
  void everyoneRoleIsFixedExceptPermissions() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    createRole(server, owner, "{\"name\":\"Mod\"}");
    String everyone = roles(server, owner).get(0).get("id").asText();
    String path = server + "/roles/" + everyone;

    assertBadRequest(call("PATCH", path, owner, "{\"name\":\"all\"}"));
    assertBadRequest(call("PATCH", path, owner, "{\"position\":1}"));
    HttpResponse<String> perms =
        call("PATCH", path, owner, "{\"permissions\":" + KICK_MEMBERS + "}");
    assertThat(perms.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(perms.body()).get("permissions").asLong()).isEqualTo(KICK_MEMBERS);
    assertThat(outbox("role.updated", everyone)).hasSize(1);
    assertBadRequest(call("DELETE", path, owner, null));
    assertThat(roles(server, owner)).hasSize(2);
  }

  // Kịch bản: đầu vào sai ở POST và PATCH bị từ chối bằng 400
  // Input: tên 101 ký tự hoặc rỗng, color 16777216 hoặc -1, permissions -1 hoặc bit lạ, position 0
  // Output: mọi trường hợp 400 VALIDATION_FAILED, số role không đổi; tên 100 ký tự được 201
  @Test
  void invalidInputReturns400() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String role = createRole(server, owner, "{\"name\":\"Mod\"}").get("id").asText();
    String[] bad = {
      "\"name\":\"" + "a".repeat(101) + "\"",
      "\"name\":\"\"",
      "\"name\":\"   \"",
      "\"color\":16777216",
      "\"color\":-1",
      "\"permissions\":-1",
      "\"permissions\":8192",
      "\"permissions\":\"abc\"",
      "\"position\":0"
    };

    for (String field : bad) {
      assertBadRequest(call("PATCH", server + "/roles/" + role, owner, "{" + field + "}"));
      String post =
          field.startsWith("\"name\"") ? "{" + field + "}" : "{\"name\":\"ok\"," + field + "}";
      assertBadRequest(call("POST", server + "/roles", owner, post));
    }
    assertBadRequest(call("POST", server + "/roles", owner, "{\"name\":\"ok\",\"position\":9}"));
    assertBadRequest(call("PATCH", server + "/roles/" + role, owner, "{\"position\":9}"));
    assertThat(roles(server, owner)).hasSize(2);
    assertThat(
            call("POST", server + "/roles", owner, "{\"name\":\"" + "a".repeat(100) + "\"}")
                .statusCode())
        .isEqualTo(201);
  }

  // Kịch bản: role không thuộc server hoặc không tồn tại là 404, id không phải UUID là 400
  // Input: owner PATCH và DELETE role của server khác, UUID lạ, và "not-a-uuid"
  // Output: 404 NOT_FOUND cho hai id đầu; "not-a-uuid" 400; role server khác không đổi
  @Test
  void unknownRoleReturns404AndMalformedIdReturns400() throws Exception {
    UUID owner = UUID.randomUUID();
    String serverA = createServer(owner);
    String serverB = createServer(owner);
    String foreign = createRole(serverB, owner, "{\"name\":\"Mod\"}").get("id").asText();

    for (String id : new String[] {foreign, UUID.randomUUID().toString()}) {
      HttpResponse<String> patch =
          call("PATCH", serverA + "/roles/" + id, owner, "{\"name\":\"zzz\"}");
      assertThat(patch.statusCode()).isEqualTo(404);
      assertThat(MAPPER.readTree(patch.body()).get("code").asText()).isEqualTo("NOT_FOUND");
      assertThat(call("DELETE", serverA + "/roles/" + id, owner, null).statusCode()).isEqualTo(404);
    }
    assertBadRequest(call("PATCH", serverA + "/roles/not-a-uuid", owner, "{\"name\":\"x\"}"));
    assertBadRequest(call("DELETE", serverA + "/roles/not-a-uuid", owner, null));
    assertThat(roleOf(serverB, owner, foreign).get("name").asText()).isEqualTo("Mod");
  }

  // Kịch bản: owner sửa đủ trường rồi xóa, outbox ghi role.updated và role.deleted
  // Input: PATCH {name,color,permissions,position} role High (vị trí 2), rồi DELETE role Low
  // Output: 200 đủ trường mới, High về vị trí 1 sau khi xóa, 204, outbox mỗi loại một dòng
  @Test
  void ownerUpdatesAndDeletesRole() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String low = createRole(server, owner, "{\"name\":\"Low\"}").get("id").asText();
    String high = createRole(server, owner, "{\"name\":\"High\"}").get("id").asText();

    HttpResponse<String> updated =
        call(
            "PATCH",
            server + "/roles/" + high,
            owner,
            "{\"name\":\" Boss \",\"color\":16,\"permissions\":"
                + BAN_MEMBERS
                + ",\"position\":2}");

    JsonNode role = MAPPER.readTree(updated.body());
    assertThat(updated.statusCode()).isEqualTo(200);
    assertThat(role.get("name").asText()).isEqualTo("Boss");
    assertThat(role.get("color").asInt()).isEqualTo(16);
    assertThat(role.get("permissions").asLong()).isEqualTo(BAN_MEMBERS);
    assertThat(role.get("position").asInt()).isEqualTo(2);
    assertThat(roleOf(server, owner, low).get("position").asInt()).isEqualTo(1);
    assertThat(outbox("role.updated", high)).hasSize(1);

    assertThat(call("DELETE", server + "/roles/" + low, owner, null).statusCode()).isEqualTo(204);

    assertThat(roleOf(server, owner, high).get("position").asInt()).isEqualTo(1);
    assertThat(outbox("role.deleted", low)).hasSize(1);
    assertThat(roles(server, owner)).hasSize(2);
  }

  // Kịch bản: xóa role đang được gán thì thành viên giữ nó mất role đó trong roleIds
  // Input: owner gán Low và High cho member qua PUT rồi DELETE role Low, GET danh sách members
  // Output: 204, roleIds của member chỉ còn High
  @Test
  void deletingAssignedRoleRemovesItFromMember() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String server = createServer(owner);
    addMember(server, member);
    String low = createRole(server, owner, "{\"name\":\"Low\"}").get("id").asText();
    String high = createRole(server, owner, "{\"name\":\"High\"}").get("id").asText();
    String roles = "{\"roleIds\":[\"" + low + "\",\"" + high + "\"]}";
    assertThat(call("PUT", server + "/members/" + member + "/roles", owner, roles).statusCode())
        .isEqualTo(200);

    assertThat(call("DELETE", server + "/roles/" + low, owner, null).statusCode()).isEqualTo(204);

    JsonNode page = MAPPER.readTree(call("GET", server + "/members", owner, null).body());
    JsonNode held =
        StreamSupport.stream(page.get("items").spliterator(), false)
            .filter(m -> m.get("userId").asText().equals(member.toString()))
            .findFirst()
            .orElseThrow();
    assertThat(held.get("roleIds")).hasSize(1);
    assertThat(held.get("roleIds").get(0).asText()).isEqualTo(high);
  }

  // Kịch bản: hai PATCH song song cùng role đọc cùng phiên bản server
  // Input: hai luồng PATCH tên khác nhau lên cùng role, cùng chờ nhau trước khi lưu
  // Output: đúng một 200 và một 409 VERSION_CONFLICT
  @Test
  void parallelPatchYieldsExactlyOneConflict() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String role = createRole(server, owner, "{\"name\":\"Mod\"}").get("id").asText();
    CyclicBarrier bothRead = new CyclicBarrier(2);
    doAnswer(
            invocation -> {
              bothRead.await(10, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .when(serverRepository)
        .save(any(Server.class));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (String name : new String[] {"Alpha", "Beta"}) {
        futures.add(
            pool.submit(
                () ->
                    call(
                        "PATCH", server + "/roles/" + role, owner, "{\"name\":\"" + name + "\"}")));
      }
      List<Integer> statuses = new ArrayList<>();
      HttpResponse<String> conflict = null;
      for (Future<HttpResponse<String>> f : futures) {
        HttpResponse<String> r = f.get();
        statuses.add(r.statusCode());
        if (r.statusCode() == 409) {
          conflict = r;
        }
      }
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
      assertThat(MAPPER.readTree(conflict.body()).get("code").asText())
          .isEqualTo("VERSION_CONFLICT");
    } finally {
      pool.shutdownNow();
    }
  }

  // Dựng server với Low (1), Lead (2, MANAGE_ROLES), High (3) và một thành viên giữ role Lead
  private Hierarchy hierarchy() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    String server = createServer(owner);
    String low = createRole(server, owner, "{\"name\":\"Low\"}").get("id").asText();
    String leadRole =
        createRole(
                server,
                owner,
                "{\"name\":\"Lead\",\"permissions\":" + MANAGE_ROLES + ",\"position\":2}")
            .get("id")
            .asText();
    String high =
        createRole(server, owner, "{\"name\":\"High\",\"position\":3}").get("id").asText();
    addMember(server, lead);
    jdbc.update(
        "insert into community.member_roles (server_id, user_id, role_id)"
            + " values (?::uuid, ?, ?::uuid)",
        server,
        lead,
        leadRole);
    return new Hierarchy(owner, lead, server, low, high);
  }

  // Kiểm tra phản hồi là 400 VALIDATION_FAILED
  private static void assertBadRequest(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(response.body()).get("code").asText())
        .isEqualTo("VALIDATION_FAILED");
  }

  // Tạo server qua REST và trả id
  private String createServer(UUID owner) throws Exception {
    HttpResponse<String> response = call("POST", "", owner, "{\"name\":\"Roles\"}");
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Tạo role qua REST, kiểm tra 201 và trả thân phản hồi
  private JsonNode createRole(String server, UUID user, String json) throws Exception {
    HttpResponse<String> response = call("POST", server + "/roles", user, json);
    assertThat(response.statusCode()).isEqualTo(201);
    return MAPPER.readTree(response.body());
  }

  // Lấy danh sách role qua GET
  private JsonNode roles(String server, UUID user) throws Exception {
    return MAPPER.readTree(call("GET", server + "/roles", user, null).body());
  }

  // Tìm role theo id trong danh sách
  private JsonNode roleOf(String server, UUID user, String roleId) throws Exception {
    for (JsonNode r : roles(server, user)) {
      if (r.get("id").asText().equals(roleId)) {
        return r;
      }
    }
    throw new AssertionError("role not found " + roleId);
  }

  // Thêm thành viên trực tiếp vào bảng members
  private void addMember(String serverId, UUID user) {
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at) values (?::uuid, ?, now())",
        serverId,
        user);
  }

  // Đọc payload outbox theo routing key và id role, cũ trước mới sau
  private List<String> outbox(String routingKey, String roleId) {
    return jdbc.queryForList(
        "select payload::text from community.outbox_event where routing_key = ?"
            + " and payload->>'roleId' = ? order by created_at",
        String.class,
        routingKey,
        roleId);
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
