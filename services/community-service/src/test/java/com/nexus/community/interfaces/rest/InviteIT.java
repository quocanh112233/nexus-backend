package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.ban.ServerBan;
import com.nexus.community.domain.ban.ServerBanRepository;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra tạo, liệt kê, xem trước, tham gia và thu hồi lời mời qua REST
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InviteIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String FUTURE = "now() + interval '1 day'";
  private static final String PAST = "now() - interval '1 minute'";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ServerBanRepository bans;
  @Autowired private TransactionTemplate tx;
  @LocalServerPort private int port;

  // Kịch bản: thành viên tạo lời mời không body và body rỗng thì dùng mặc định
  // Input: member POST /servers/{id}/invites không body, rồi với body {}
  // Output: 201, mã 8 ký tự, maxUses 0, uses 0, hạn khoảng now + 7 ngày, inviterId là member
  @Test
  void memberCreatesInviteWithDefaults() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String server = createServer(owner);
    addMember(server, member);

    for (String body : new String[] {null, "{}"}) {
      HttpResponse<String> response = call("POST", invitesOf(server), bearer(member), body);
      JsonNode invite = MAPPER.readTree(response.body());
      assertThat(response.statusCode()).isEqualTo(201);
      assertThat(invite.get("code").asText()).matches("[A-Za-z0-9]{8}");
      assertThat(invite.get("serverId").asText()).isEqualTo(server);
      assertThat(invite.get("inviterId").asText()).isEqualTo(member.toString());
      assertThat(invite.get("maxUses").asInt()).isZero();
      assertThat(invite.get("uses").asInt()).isZero();
      assertThat(Instant.parse(invite.get("expiresAt").asText()))
          .isCloseTo(Instant.now().plus(Duration.ofDays(7)), within(1, ChronoUnit.MINUTES));
    }
  }

  // Kịch bản: người ngoài server không tạo được lời mời
  // Input: user lạ POST /servers/{id}/invites, và server không tồn tại
  // Output: cả hai 404 NOT_FOUND
  @Test
  void nonMemberCreate404() throws Exception {
    String server = createServer(UUID.randomUUID());
    UUID stranger = UUID.randomUUID();

    for (String id : new String[] {server, UUID.randomUUID().toString()}) {
      HttpResponse<String> response = call("POST", invitesOf(id), bearer(stranger), "{}");
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(code(response)).isEqualTo("NOT_FOUND");
    }
  }

  // Kịch bản: maxUses và expiresInSeconds ngoài khoảng bị từ chối, biên thì nhận
  // Input: maxUses -1, 1001; expiresInSeconds 59, 2592001; rồi maxUses 1000 + 60 giây
  // Output: bốn 400 VALIDATION_FAILED, lần cuối 201 với maxUses 1000
  @Test
  void maxUsesOutOfRange400() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);

    for (String body :
        new String[] {
          "{\"maxUses\":-1}",
          "{\"maxUses\":1001}",
          "{\"expiresInSeconds\":59}",
          "{\"expiresInSeconds\":2592001}"
        }) {
      HttpResponse<String> response = call("POST", invitesOf(server), bearer(owner), body);
      assertThat(response.statusCode()).as(body).isEqualTo(400);
      assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
    }
    HttpResponse<String> ok =
        call(
            "POST", invitesOf(server), bearer(owner), "{\"maxUses\":1000,\"expiresInSeconds\":60}");
    assertThat(ok.statusCode()).isEqualTo(201);
    assertThat(MAPPER.readTree(ok.body()).get("maxUses").asInt()).isEqualTo(1000);
  }

  // Kịch bản: chủ server liệt kê lời mời, mới tạo trước
  // Input: owner tạo lời mời A rồi B, rồi GET /servers/{id}/invites
  // Output: 200 mảng [B, A]
  @Test
  void ownerListsInvites() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String a = createInvite(server, owner, "{}");
    String b = createInvite(server, owner, "{}");

    HttpResponse<String> response = call("GET", invitesOf(server), bearer(owner), null);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode list = MAPPER.readTree(response.body());
    assertThat(list).hasSize(2);
    assertThat(list.get(0).get("code").asText()).isEqualTo(b);
    assertThat(list.get(1).get("code").asText()).isEqualTo(a);
  }

  // Kịch bản: thành viên không phải chủ không liệt kê được, người ngoài nhận 404
  // Input: member và user lạ GET /servers/{id}/invites
  // Output: member 403 PERMISSION_DENIED, người ngoài 404 NOT_FOUND
  @Test
  void memberListGets403() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String server = createServer(owner);
    addMember(server, member);

    HttpResponse<String> forbidden = call("GET", invitesOf(server), bearer(member), null);
    HttpResponse<String> hidden = call("GET", invitesOf(server), bearer(UUID.randomUUID()), null);

    assertThat(forbidden.statusCode()).isEqualTo(403);
    assertThat(code(forbidden)).isEqualTo("PERMISSION_DENIED");
    assertThat(hidden.statusCode()).isEqualTo(404);
  }

  // Kịch bản: xem trước lời mời không cần JWT và đếm thành viên
  // Input: server có owner + 1 member, GET /invites/{code} không header Authorization
  // Output: 200 với code, serverName "Inv", memberCount 2, expiresAt của lời mời
  @Test
  void previewIsPublicAndShowsMemberCount() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    addMember(server, UUID.randomUUID());
    JsonNode invite = MAPPER.readTree(call("POST", invitesOf(server), bearer(owner), "{}").body());
    String code = invite.get("code").asText();

    HttpResponse<String> response = call("GET", "/api/v1/invites/" + code, null, null);

    JsonNode preview = MAPPER.readTree(response.body());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(preview.get("code").asText()).isEqualTo(code);
    assertThat(preview.get("serverName").asText()).isEqualTo("Inv");
    assertThat(preview.get("memberCount").asLong()).isEqualTo(2);
    assertThat(preview.get("expiresAt").asText()).isEqualTo(invite.get("expiresAt").asText());
  }

  // Kịch bản: Bearer hết hạn hoặc rác không chặn trang xem trước công khai, nhưng vẫn chặn accept
  // Input: GET /invites/{code} với token hết hạn và "garbage"; POST accept với "garbage"
  // Output: hai GET 200; POST accept 401 UNAUTHENTICATED
  @Test
  void previewIgnoresStaleOrGarbageBearer() throws Exception {
    UUID owner = UUID.randomUUID();
    String code = createInvite(createServer(owner), owner, "{}");
    String expired = "Bearer " + JWT.token(owner, "old", Instant.now().minus(Duration.ofHours(1)));

    for (String auth : new String[] {expired, "Bearer garbage"}) {
      assertThat(call("GET", "/api/v1/invites/" + code, auth, null).statusCode())
          .as(auth)
          .isEqualTo(200);
    }
    HttpResponse<String> accept =
        call("POST", "/api/v1/invites/" + code + "/accept", "Bearer garbage", null);
    assertThat(accept.statusCode()).isEqualTo(401);
    assertThat(code(accept)).isEqualTo("UNAUTHENTICATED");
  }

  // Kịch bản: lời mời hết hạn, thu hồi, hết lượt, không có hoặc sai định dạng không xem trước được
  // Input: GET /invites/{code} cho từng loại mã
  // Output: tất cả 404 NOT_FOUND
  @Test
  void previewExpiredRevokedUnknownAndBadFormatReturn404() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);

    for (String code : badCodes(server, owner)) {
      HttpResponse<String> response = call("GET", "/api/v1/invites/" + code, null, null);
      assertThat(response.statusCode()).as(code).isEqualTo(404);
      assertThat(code(response)).isEqualTo("NOT_FOUND");
    }
  }

  // Kịch bản: tham gia cần đăng nhập
  // Input: POST /invites/{code}/accept không header Authorization
  // Output: 401 UNAUTHENTICATED
  @Test
  void acceptRequiresBearer() throws Exception {
    UUID owner = UUID.randomUUID();
    String code = createInvite(createServer(owner), owner, "{}");

    HttpResponse<String> response = call("POST", "/api/v1/invites/" + code + "/accept", null, null);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(code(response)).isEqualTo("UNAUTHENTICATED");
  }

  // Kịch bản: user mới tham gia bằng lời mời
  // Input: user POST /invites/{code}/accept
  // Output: 200 tóm tắt server, có hàng members, uses 1, outbox member.joined một dòng cho user
  @Test
  void acceptAddsMemberAndOutboxMemberJoined() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    String server = createServer(owner);
    String code = createInvite(server, owner, "{}");

    HttpResponse<String> response = accept(code, user);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode summary = MAPPER.readTree(response.body());
    assertThat(summary.get("id").asText()).isEqualTo(server);
    assertThat(summary.get("name").asText()).isEqualTo("Inv");
    assertThat(isMember(server, user)).isTrue();
    assertThat(uses(code)).isEqualTo(1);
    assertThat(joinedOutbox(server, user)).isEqualTo(1);
  }

  // Kịch bản: tham gia hai lần không tiêu thêm lượt và không phát thêm event
  // Input: cùng user accept hai lần
  // Output: cả hai 200, uses 1, outbox member.joined của user một dòng
  @Test
  void acceptTwiceIsIdempotent200() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    String server = createServer(owner);
    String code = createInvite(server, owner, "{}");

    assertThat(accept(code, user).statusCode()).isEqualTo(200);
    assertThat(accept(code, user).statusCode()).isEqualTo(200);

    assertThat(uses(code)).isEqualTo(1);
    assertThat(joinedOutbox(server, user)).isEqualTo(1);
  }

  // Kịch bản: user mới không tham gia được bằng lời mời hết hạn, thu hồi, hết lượt, lạ, sai dạng
  // Input: POST /invites/{code}/accept cho từng loại mã
  // Output: tất cả 410 INVITE_INVALID, user không thành member, không có member.joined
  @Test
  void expiredRevokedExhaustedUnknownBadFormatAcceptReturn410() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    String server = createServer(owner);

    for (String code : badCodes(server, owner)) {
      HttpResponse<String> response = accept(code, user);
      assertThat(response.statusCode()).as(code).isEqualTo(410);
      assertThat(code(response)).isEqualTo("INVITE_INVALID");
    }
    assertThat(isMember(server, user)).isFalse();
    assertThat(joinedOutbox(server, user)).isZero();
  }

  // Kịch bản: user đang bị ban không tham gia được dù lời mời còn dùng được
  // Input: ban user qua repository rồi user đó accept; sau đó một user khác accept
  // Output: bị ban 403 BANNED_FROM_SERVER, không member, uses 0, không member.joined; user khác 200
  @Test
  void bannedUserAccept403WithoutConsumingUse() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID banned = UUID.randomUUID();
    String server = createServer(owner);
    String code = createInvite(server, owner, "{\"maxUses\":5}");
    ban(server, banned);

    HttpResponse<String> response = accept(code, banned);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("BANNED_FROM_SERVER");
    assertThat(isMember(server, banned)).isFalse();
    assertThat(uses(code)).isZero();
    assertThat(joinedOutbox(server, banned)).isZero();
    assertThat(accept(code, UUID.randomUUID()).statusCode()).isEqualTo(200);
    assertThat(uses(code)).isEqualTo(1);
  }

  // Kịch bản: ban chỉ có hiệu lực trong đúng server bị ban
  // Input: user bị ban ở server A accept lời mời của server B
  // Output: 200 và user thành member của server B
  @Test
  void banInOtherServerDoesNotBlock() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    String serverA = createServer(owner);
    String serverB = createServer(owner);
    String codeB = createInvite(serverB, owner, "{}");
    ban(serverA, user);

    assertThat(accept(codeB, user).statusCode()).isEqualTo(200);
    assertThat(isMember(serverB, user)).isTrue();
  }

  // Kịch bản: người đã là thành viên accept lời mời hết hạn vẫn nhận 200
  // Input: owner accept lời mời hết hạn của chính server
  // Output: 200 tóm tắt server, uses vẫn 0
  @Test
  void alreadyMemberCanAcceptExpiredInvite200() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String code = insertInvite(server, owner, 0, 0, PAST, "null");

    HttpResponse<String> response = accept(code, owner);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(response.body()).get("id").asText()).isEqualTo(server);
    assertThat(uses(code)).isZero();
  }

  // Kịch bản: lời mời không giới hạn lượt cho nhiều người tham gia
  // Input: lời mời maxUses 0, bảy user khác nhau accept
  // Output: cả bảy 200, uses 7, server có 8 thành viên
  @Test
  void unlimitedInviteAcceptedBySevenUsers() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String code = createInvite(server, owner, "{\"maxUses\":0}");

    for (int i = 0; i < 7; i++) {
      assertThat(accept(code, UUID.randomUUID()).statusCode()).isEqualTo(200);
    }

    assertThat(uses(code)).isEqualTo(7);
    assertThat(memberCount(server)).isEqualTo(8);
  }

  // Kịch bản: người tạo hoặc chủ server thu hồi được lời mời
  // Input: member tạo A và tự thu hồi; member tạo B và owner thu hồi
  // Output: hai 204, revoked_at được ghi, xem trước 404 và accept 410
  @Test
  void revokeByCreatorOrOwnerOk() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String server = createServer(owner);
    addMember(server, member);
    String a = createInvite(server, member, "{}");
    String b = createInvite(server, member, "{}");

    assertThat(call("DELETE", "/api/v1/invites/" + a, bearer(member), null).statusCode())
        .isEqualTo(204);
    assertThat(call("DELETE", "/api/v1/invites/" + b, bearer(owner), null).statusCode())
        .isEqualTo(204);

    for (String code : new String[] {a, b}) {
      assertThat(
              jdbc.queryForObject(
                  "select revoked_at is not null from community.invites where code = ?",
                  Boolean.class,
                  code))
          .isTrue();
      assertThat(call("GET", "/api/v1/invites/" + code, null, null).statusCode()).isEqualTo(404);
      assertThat(accept(code, UUID.randomUUID()).statusCode()).isEqualTo(410);
    }
  }

  // Kịch bản: thành viên khác không phải người tạo hay chủ không thu hồi được
  // Input: owner tạo lời mời, member khác DELETE
  // Output: 403 PERMISSION_DENIED, lời mời vẫn dùng được
  @Test
  void revokeByOtherMember403() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String server = createServer(owner);
    addMember(server, member);
    String code = createInvite(server, owner, "{}");

    HttpResponse<String> response = call("DELETE", "/api/v1/invites/" + code, bearer(member), null);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("PERMISSION_DENIED");
    assertThat(call("GET", "/api/v1/invites/" + code, null, null).statusCode()).isEqualTo(200);
  }

  // Kịch bản: thu hồi mã lạ, sai định dạng hoặc lời mời của server mình không thuộc
  // Input: DELETE mã "Zz9Zz9Zz", "short", và người ngoài DELETE lời mời có thật
  // Output: tất cả 404 NOT_FOUND, lời mời thật vẫn dùng được
  @Test
  void revokeUnknown404() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    String code = createInvite(createServer(owner), owner, "{}");

    for (String c : new String[] {"Zz9Zz9Zz", "short", code}) {
      HttpResponse<String> response =
          call("DELETE", "/api/v1/invites/" + c, bearer(stranger), null);
      assertThat(response.statusCode()).as(c).isEqualTo(404);
      assertThat(code(response)).isEqualTo("NOT_FOUND");
    }
    assertThat(call("GET", "/api/v1/invites/" + code, null, null).statusCode()).isEqualTo(200);
  }

  // Dựng mã hết hạn, thu hồi, hết lượt, không có và sai định dạng cho server
  private String[] badCodes(String server, UUID owner) {
    return new String[] {
      insertInvite(server, owner, 0, 0, PAST, "null"),
      insertInvite(server, owner, 0, 0, FUTURE, "now()"),
      insertInvite(server, owner, 1, 1, FUTURE, "null"),
      "Zz9Zz9Zz",
      "short"
    };
  }

  // Chèn lời mời trực tiếp bằng SQL để có hạn quá khứ, thu hồi hoặc hết lượt
  private String insertInvite(
      String server, UUID inviter, int maxUses, int uses, String expiresSql, String revokedSql) {
    String code = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    jdbc.update(
        "insert into community.invites"
            + " (code, server_id, inviter_id, max_uses, uses, expires_at, revoked_at, created_at)"
            + " values (?, ?::uuid, ?, ?, ?, "
            + expiresSql
            + ", "
            + revokedSql
            + ", now())",
        code,
        server,
        inviter,
        maxUses,
        uses);
    return code;
  }

  // Tạo server tên "Inv" qua REST và trả id
  private String createServer(UUID owner) throws Exception {
    HttpResponse<String> response =
        call("POST", "/api/v1/servers", bearer(owner), "{\"name\":\"Inv\"}");
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Tạo lời mời qua REST và trả mã
  private String createInvite(String server, UUID user, String body) throws Exception {
    HttpResponse<String> response = call("POST", invitesOf(server), bearer(user), body);
    assertThat(response.statusCode()).isEqualTo(201);
    return MAPPER.readTree(response.body()).get("code").asText();
  }

  // Đường dẫn danh sách lời mời của server
  private static String invitesOf(String server) {
    return "/api/v1/servers/" + server + "/invites";
  }

  // Ban user khỏi server qua repository trong một transaction
  private void ban(String server, UUID user) {
    ServerBan ban =
        ServerBan.create(
            new ServerId(UUID.fromString(server)),
            new UserId(user),
            UserId.newId(),
            null,
            false,
            Instant.now().truncatedTo(ChronoUnit.MICROS));
    tx.executeWithoutResult(s -> bans.addIfAbsent(ban));
  }

  // Gọi accept lời mời với Bearer của user
  private HttpResponse<String> accept(String code, UUID user) throws Exception {
    return call("POST", "/api/v1/invites/" + code + "/accept", bearer(user), null);
  }

  // Thêm thành viên trực tiếp vào bảng members
  private void addMember(String serverId, UUID user) {
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at) values (?::uuid, ?, now())",
        serverId,
        user);
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

  // Đếm thành viên của server
  private long memberCount(String server) {
    return jdbc.queryForObject(
        "select count(*) from community.members where server_id = ?::uuid", Long.class, server);
  }

  // Đọc uses của lời mời
  private int uses(String code) {
    return jdbc.queryForObject(
        "select uses from community.invites where code = ?", Integer.class, code);
  }

  // Đếm dòng outbox member.joined của user trong server
  private long joinedOutbox(String server, UUID user) {
    return jdbc.queryForObject(
        "select count(*) from community.outbox_event where routing_key = 'member.joined'"
            + " and payload->>'serverId' = ? and payload->>'userId' = ?",
        Long.class,
        server,
        user.toString());
  }

  // Đọc trường code của Problem Details
  private static String code(HttpResponse<String> response) throws Exception {
    return MAPPER.readTree(response.body()).get("code").asText();
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
