package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.member.MemberRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra gán role cho thành viên qua REST: thứ bậc, đầu vào, phân quyền và outbox
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemberRolesIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final long MANAGE_ROLES = 4;

  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private MemberRepository memberRepository;
  @LocalServerPort private int port;

  // Dữ liệu dựng sẵn: owner, Lead giữ role Lead (2), member thường, ba role Low 1, Lead 2, High 3
  private record Hierarchy(
      UUID owner,
      UUID lead,
      UUID member,
      String server,
      String low,
      String leadRole,
      String high) {}

  // Kịch bản: owner gán role cho thành viên và GET members thấy roleIds mới
  // Input: owner PUT roleIds [Low, High] cho member rồi GET danh sách thành viên
  // Output: 200 kèm userId, joinedAt, roleIds đúng hai role; GET members trả cùng roleIds
  @Test
  void ownerAssignsRoles() throws Exception {
    Hierarchy h = hierarchy();

    HttpResponse<String> response = put(h, h.owner, h.member, ids(h.low, h.high));

    JsonNode body = MAPPER.readTree(response.body());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(body.get("userId").asText()).isEqualTo(h.member.toString());
    assertThat(body.get("joinedAt").asText()).isNotBlank();
    assertThat(texts(body.get("roleIds"))).containsExactlyInAnyOrder(h.low, h.high);
    assertThat(texts(memberOf(h, h.member).get("roleIds")))
        .containsExactlyInAnyOrder(h.low, h.high);
  }

  // Kịch bản: Lead (vị trí 2) chỉ gán được role thấp hơn mình cho người khác
  // Input: Lead PUT cho member [Low], rồi [Lead], [High], [Low, High]
  // Output: [Low] 200; ba lần sau 403 PERMISSION_DENIED và member vẫn chỉ giữ Low
  @Test
  void leadAssignsOnlyLowerRoles() throws Exception {
    Hierarchy h = hierarchy();

    assertThat(put(h, h.lead, h.member, ids(h.low)).statusCode()).isEqualTo(200);
    for (List<String> roles :
        List.of(ids(h.leadRole), ids(h.high), ids(h.low, h.high), ids(h.low, h.leadRole))) {
      HttpResponse<String> denied = put(h, h.lead, h.member, roles);
      assertThat(denied.statusCode()).isEqualTo(403);
      assertThat(MAPPER.readTree(denied.body()).get("code").asText())
          .isEqualTo("PERMISSION_DENIED");
    }
    assertThat(texts(memberOf(h, h.member).get("roleIds"))).containsExactly(h.low);
  }

  // Kịch bản: tự gán role cao hơn mình cũng bị chặn, tự gán role thấp thì được
  // Input: Lead PUT cho chính mình [Lead, High], rồi [Lead, Low]
  // Output: lần đầu 403 và roleIds không đổi; lần hai 200 với roleIds gồm Lead và Low
  @Test
  void selfAssignFollowsSameRule() throws Exception {
    Hierarchy h = hierarchy();

    assertThat(put(h, h.lead, h.lead, ids(h.leadRole, h.high)).statusCode()).isEqualTo(403);
    assertThat(texts(memberOf(h, h.lead).get("roleIds"))).containsExactly(h.leadRole);
    assertThat(put(h, h.lead, h.lead, ids(h.leadRole, h.low)).statusCode()).isEqualTo(200);
    assertThat(texts(memberOf(h, h.lead).get("roleIds")))
        .containsExactlyInAnyOrder(h.leadRole, h.low);
  }

  // Kịch bản: gỡ role cao hơn mình khỏi người khác bị chặn vì hiệu đối xứng gồm cả role bị bớt
  // Input: owner gán member [High, Low]; Lead PUT cho member [Low], rồi []
  // Output: cả hai 403 và member vẫn giữ High và Low
  @Test
  void removingHigherRoleIsForbidden() throws Exception {
    Hierarchy h = hierarchy();
    put(h, h.owner, h.member, ids(h.high, h.low));

    assertThat(put(h, h.lead, h.member, ids(h.low)).statusCode()).isEqualTo(403);
    assertThat(put(h, h.lead, h.member, ids()).statusCode()).isEqualTo(403);

    assertThat(texts(memberOf(h, h.member).get("roleIds")))
        .containsExactlyInAnyOrder(h.high, h.low);
  }

  // Kịch bản: roleIds rỗng gỡ hết role và phát thêm một sự kiện
  // Input: owner gán member [Low, High] rồi PUT []
  // Output: 200 roleIds rỗng, GET members rỗng, outbox có hai member.roles-changed, mới nhất rỗng
  @Test
  void emptyListClearsAllRoles() throws Exception {
    Hierarchy h = hierarchy();
    put(h, h.owner, h.member, ids(h.low, h.high));

    HttpResponse<String> response = put(h, h.owner, h.member, ids());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(response.body()).get("roleIds")).isEmpty();
    assertThat(memberOf(h, h.member).get("roleIds")).isEmpty();
    List<String> events = outbox(h);
    assertThat(events).hasSize(2);
    assertThat(MAPPER.readTree(events.get(1)).get("roleIds")).isEmpty();
  }

  // Kịch bản: roleIds sai bị từ chối 400 và không đổi gì
  // Input: PUT id lạ, id trùng, id @everyone, thiếu roleIds, roleIds null, phần tử null, UUID hỏng
  // Output: mọi trường hợp 400 VALIDATION_FAILED, member vẫn không có role và outbox không có dòng
  @Test
  void invalidRoleIdsReturn400() throws Exception {
    Hierarchy h = hierarchy();
    String everyone = roles(h).get(0).get("id").asText();
    String[] bodies = {
      "{\"roleIds\":[\"" + UUID.randomUUID() + "\"]}",
      "{\"roleIds\":[\"" + h.low + "\",\"" + h.low + "\"]}",
      "{\"roleIds\":[\"" + everyone + "\"]}",
      "{}",
      "{\"roleIds\":null}",
      "{\"roleIds\":[null]}",
      "{\"roleIds\":[\"not-a-uuid\"]}"
    };

    for (String json : bodies) {
      assertBadRequest(raw("PUT", path(h, h.member), h.owner, json));
    }
    assertBadRequest(raw("PUT", path(h, h.member), h.owner, "{\"roleIds\":"));

    assertThat(memberOf(h, h.member).get("roleIds")).isEmpty();
    assertThat(outbox(h)).isEmpty();
  }

  // Kịch bản: userId trong đường dẫn hỏng là 400, đích không phải thành viên là 404
  // Input: owner PUT tới /members/not-a-uuid và tới user chưa vào server
  // Output: 400 VALIDATION_FAILED cho id hỏng; 404 NOT_FOUND cho người chưa vào server
  @Test
  void malformedTargetIs400AndUnknownTargetIs404() throws Exception {
    Hierarchy h = hierarchy();
    String body = "{\"roleIds\":[\"" + h.low + "\"]}";

    assertBadRequest(raw("PUT", path(h, "not-a-uuid"), h.owner, body));
    HttpResponse<String> missing = put(h, h.owner, UUID.randomUUID(), ids(h.low));

    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(missing.body()).get("code").asText()).isEqualTo("NOT_FOUND");
  }

  // Kịch bản: người ngoài server nhận 404, thành viên thiếu MANAGE_ROLES nhận 403
  // Input: người lạ và member thường PUT roleIds [Low] cho member khác
  // Output: người lạ 404, member thường 403 và roleIds của đích không đổi
  @Test
  void strangerGets404AndMemberWithoutPermissionGets403() throws Exception {
    Hierarchy h = hierarchy();
    UUID other = UUID.randomUUID();
    addMember(h.server, other);

    assertThat(put(h, UUID.randomUUID(), h.member, ids(h.low)).statusCode()).isEqualTo(404);
    assertThat(put(h, other, h.member, ids(h.low)).statusCode()).isEqualTo(403);

    assertThat(memberOf(h, h.member).get("roleIds")).isEmpty();
  }

  // Kịch bản: gán lại đúng tập role không phát thêm sự kiện, đổi thật thì phát đúng một
  // Input: owner PUT [Low, High], lại [Low, High], rồi [High, Low] khác thứ tự
  // Output: cả ba 200; outbox đúng một member.roles-changed với roleIds Low và High dạng chuỗi
  @Test
  void sameSetEmitsNoExtraEvent() throws Exception {
    Hierarchy h = hierarchy();

    assertThat(put(h, h.owner, h.member, ids(h.low, h.high)).statusCode()).isEqualTo(200);
    assertThat(outbox(h)).hasSize(1);
    assertThat(put(h, h.owner, h.member, ids(h.low, h.high)).statusCode()).isEqualTo(200);
    assertThat(put(h, h.owner, h.member, ids(h.high, h.low)).statusCode()).isEqualTo(200);

    List<String> events = outbox(h);
    assertThat(events).hasSize(1);
    JsonNode payload = MAPPER.readTree(events.get(0));
    assertThat(payload.get("userId").asText()).isEqualTo(h.member.toString());
    assertThat(texts(payload.get("roleIds"))).containsExactlyInAnyOrder(h.low, h.high);
  }

  // Kịch bản: Lead đọc roleIds cũ của member rồi owner gỡ High và commit trước khi Lead ghi
  // Input: member giữ High; Lead PUT [High, Low] dừng sau khi đọc, owner PUT [] chạy xong, Lead ghi
  // Output: khóa hàng member tuần tự hóa hai PUT nên roleIds cuối cùng không còn High
  @Test
  void lowerActorCannotRestoreRoleRemovedConcurrently() throws Exception {
    Hierarchy h = hierarchy();
    put(h, h.owner, h.member, ids(h.high));
    CountDownLatch leadRead = new CountDownLatch(1);
    CountDownLatch ownerDone = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean();
    doAnswer(
            invocation -> {
              Object read = invocation.callRealMethod();
              if (first.compareAndSet(false, true)) {
                leadRead.countDown();
                ownerDone.await(2, TimeUnit.SECONDS);
              }
              return read;
            })
        .when(memberRepository)
        .findForUpdate(any(), any());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<HttpResponse<String>> lead =
          pool.submit(() -> put(h, h.lead, h.member, ids(h.high, h.low)));
      assertThat(leadRead.await(10, TimeUnit.SECONDS)).isTrue();
      Future<HttpResponse<String>> owner =
          pool.submit(
              () -> {
                HttpResponse<String> response = put(h, h.owner, h.member, ids());
                ownerDone.countDown();
                return response;
              });

      assertThat(owner.get().statusCode()).isEqualTo(200);
      assertThat(lead.get().statusCode()).isIn(200, 403);
    } finally {
      pool.shutdownNow();
    }
    assertThat(texts(memberOf(h, h.member).get("roleIds"))).doesNotContain(h.high);
  }

  // Kịch bản: role bị xóa sau khi use case nạp server nhưng trước khi ghi member_roles
  // Input: owner PUT [Low] dừng sau khi đọc, role Low bị DELETE và commit, rồi PUT ghi tiếp
  // Output: vi phạm khóa ngoại thành 409 VERSION_CONFLICT, member không có role nào
  @Test
  void roleDeletedDuringPutReturns409() throws Exception {
    Hierarchy h = hierarchy();
    CountDownLatch read = new CountDownLatch(1);
    CountDownLatch deleted = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean();
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              if (first.compareAndSet(false, true)) {
                read.countDown();
                deleted.await(10, TimeUnit.SECONDS);
              }
              return result;
            })
        .when(memberRepository)
        .findForUpdate(any(), any());
    ExecutorService pool = Executors.newFixedThreadPool(1);
    try {
      Future<HttpResponse<String>> assign =
          pool.submit(() -> put(h, h.owner, h.member, ids(h.low)));
      assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(raw("DELETE", h.server + "/roles/" + h.low, h.owner, null).statusCode())
          .isEqualTo(204);
      deleted.countDown();

      HttpResponse<String> response = assign.get();

      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(MAPPER.readTree(response.body()).get("code").asText())
          .isEqualTo("VERSION_CONFLICT");
    } finally {
      pool.shutdownNow();
    }
    assertThat(memberOf(h, h.member).get("roleIds")).isEmpty();
    assertThat(outbox(h)).isEmpty();
  }

  // Kịch bản: member được gán thêm một role mới mà server nạp trước đó chưa biết
  // Input: trước khi PUT đọc member, chèn role vị trí 50 và gán cho member, rồi owner PUT []
  // Output: 409 VERSION_CONFLICT thay vì 500, member vẫn giữ role vừa chèn
  @Test
  void roleUnknownToLoadedServerReturns409() throws Exception {
    Hierarchy h = hierarchy();
    UUID extra = UUID.randomUUID();
    AtomicBoolean first = new AtomicBoolean();
    doAnswer(
            invocation -> {
              if (first.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> insertExtraRole(h, extra)).join();
              }
              return invocation.callRealMethod();
            })
        .when(memberRepository)
        .findForUpdate(any(), any());

    HttpResponse<String> response = put(h, h.owner, h.member, ids());

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(MAPPER.readTree(response.body()).get("code").asText()).isEqualTo("VERSION_CONFLICT");
    assertThat(texts(memberOf(h, h.member).get("roleIds"))).containsExactly(extra.toString());
  }

  // Dựng server với Low (1), Lead (2, MANAGE_ROLES), High (3), Lead giữ role Lead, thêm member
  private Hierarchy hierarchy() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String server = createServer(owner);
    String low = createRole(server, owner, "{\"name\":\"Low\"}");
    String leadRole =
        createRole(
            server,
            owner,
            "{\"name\":\"Lead\",\"permissions\":" + MANAGE_ROLES + ",\"position\":2}");
    String high = createRole(server, owner, "{\"name\":\"High\",\"position\":3}");
    addMember(server, lead);
    addMember(server, member);
    jdbc.update(
        "insert into community.member_roles (server_id, user_id, role_id)"
            + " values (?::uuid, ?, ?::uuid)",
        server,
        lead,
        leadRole);
    return new Hierarchy(owner, lead, member, server, low, leadRole, high);
  }

  // Chèn role vị trí 50 và gán cho member bằng connection riêng để commit độc lập với request
  private void insertExtraRole(Hierarchy h, UUID role) {
    jdbc.update(
        "insert into community.roles (id, server_id, name, color, permissions,"
            + " position, is_default, created_at)"
            + " values (?, ?::uuid, 'Extra', 0, 0, 50, false, now())",
        role,
        h.server);
    jdbc.update(
        "insert into community.member_roles (server_id, user_id, role_id)"
            + " values (?::uuid, ?, ?)",
        h.server,
        h.member,
        role);
  }

  // Gom các id role thành danh sách
  private static List<String> ids(String... roles) {
    return List.of(roles);
  }

  // Đọc các chuỗi của một mảng JSON
  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

  // Kiểm tra phản hồi là 400 VALIDATION_FAILED
  private static void assertBadRequest(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(response.body()).get("code").asText())
        .isEqualTo("VALIDATION_FAILED");
  }

  // Đường dẫn gán role cho đích
  private static String path(Hierarchy h, Object target) {
    return h.server + "/members/" + target + "/roles";
  }

  // Gửi PUT roleIds do actor gọi cho đích
  private HttpResponse<String> put(Hierarchy h, UUID actor, UUID target, List<String> roleIds)
      throws Exception {
    String json =
        "{\"roleIds\":["
            + String.join(",", roleIds.stream().map(r -> "\"" + r + "\"").toList())
            + "]}";
    return raw("PUT", path(h, target), actor, json);
  }

  // Tạo server tên "Assign" qua REST và trả id
  private String createServer(UUID owner) throws Exception {
    HttpResponse<String> response = raw("POST", "", owner, "{\"name\":\"Assign\"}");
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Tạo role qua REST, kiểm tra 201 và trả id
  private String createRole(String server, UUID owner, String json) throws Exception {
    HttpResponse<String> response = raw("POST", server + "/roles", owner, json);
    assertThat(response.statusCode()).isEqualTo(201);
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Lấy danh sách role qua GET
  private JsonNode roles(Hierarchy h) throws Exception {
    return MAPPER.readTree(raw("GET", h.server + "/roles", h.owner, null).body());
  }

  // Lấy một thành viên trong trang đầu của GET members
  private JsonNode memberOf(Hierarchy h, UUID user) throws Exception {
    JsonNode page = MAPPER.readTree(raw("GET", h.server + "/members", h.owner, null).body());
    for (JsonNode m : page.get("items")) {
      if (m.get("userId").asText().equals(user.toString())) {
        return m;
      }
    }
    throw new AssertionError("member not found " + user);
  }

  // Thêm thành viên trực tiếp vào bảng members
  private void addMember(String serverId, UUID user) {
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at) values (?::uuid, ?, now())",
        serverId,
        user);
  }

  // Đọc payload outbox member.roles-changed của đích mặc định là member, cũ trước mới sau
  private List<String> outbox(Hierarchy h) {
    return jdbc.queryForList(
        "select payload::text from community.outbox_event where routing_key ="
            + " 'member.roles-changed' and aggregate_id = ?::uuid and payload->>'userId' = ?"
            + " order by created_at",
        String.class,
        h.server,
        h.member.toString());
  }

  // Gọi method trên /api/v1/servers/{path} với Bearer của user, body null là không gửi body
  private HttpResponse<String> raw(String method, String path, UUID user, String json)
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
