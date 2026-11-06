package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra các endpoint quản trị đòi quyền MANAGE_*, CREATE_INVITE thay cho check chủ server
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PermissionEnforcementIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final long ADMINISTRATOR = 1;
  private static final long MANAGE_SERVER = 2;
  private static final long MANAGE_ROLES = 4;
  private static final long MANAGE_CHANNELS = 8;
  private static final long CREATE_INVITE = 16;
  private static final long VIEW_CHANNEL = 32;
  private static final long EVERYONE_DEFAULT = 1008;

  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private ChannelRepository channelRepository;
  @LocalServerPort private int port;

  // Endpoint cần quyền, kèm quyền yêu cầu và mã thành công
  private enum Endpoint {
    RENAME_SERVER(MANAGE_SERVER, 200),
    CREATE_CATEGORY(MANAGE_CHANNELS, 201),
    UPDATE_CATEGORY(MANAGE_CHANNELS, 200),
    DELETE_CATEGORY(MANAGE_CHANNELS, 204),
    CREATE_CHANNEL(MANAGE_CHANNELS, 201),
    UPDATE_CHANNEL(MANAGE_CHANNELS, 200),
    DELETE_CHANNEL(MANAGE_CHANNELS, 204),
    LIST_INVITES(MANAGE_SERVER, 200),
    CREATE_INVITE_AT(CREATE_INVITE, 201),
    REVOKE_INVITE(MANAGE_SERVER, 204);

    private final long permission;
    private final int success;

    // Gán quyền yêu cầu và mã thành công
    Endpoint(long permission, int success) {
      this.permission = permission;
      this.success = success;
    }
  }

  // Dữ liệu dựng sẵn: chủ server, một thành viên thường và id server
  private record Fixture(UUID owner, UUID member, String server) {}

  // Kịch bản: thành viên thường chỉ tạo được invite, mọi endpoint quản trị khác bị chặn
  // Input: member chỉ có @everyone gọi từng endpoint trong danh sách
  // Output: tạo invite 201, các endpoint còn lại 403 PERMISSION_DENIED
  @Test
  void plainMemberIsDeniedExceptCreateInvite() throws Exception {
    for (Endpoint endpoint : Endpoint.values()) {
      Fixture fx = fixture();

      HttpResponse<String> response = invoke(endpoint, fx, fx.member);

      if (endpoint == Endpoint.CREATE_INVITE_AT) {
        assertThat(response.statusCode()).as(endpoint.name()).isEqualTo(201);
      } else {
        assertThat(response.statusCode()).as(endpoint.name()).isEqualTo(403);
        assertThat(code(response)).as(endpoint.name()).isEqualTo("PERMISSION_DENIED");
      }
    }
  }

  // Kịch bản: member giữ role chỉ có đúng quyền của endpoint thì gọi thành công
  // Input: owner tạo role với đúng một bit quyền, gán cho member, member gọi endpoint tương ứng
  // Output: mọi endpoint trả mã thành công của nó
  @Test
  void memberWithExactPermissionSucceeds() throws Exception {
    for (Endpoint endpoint : Endpoint.values()) {
      Fixture fx = fixture();
      grantRole(fx, endpoint.permission);

      HttpResponse<String> response = invoke(endpoint, fx, fx.member);

      assertThat(response.statusCode()).as(endpoint.name()).isEqualTo(endpoint.success);
    }
  }

  // Kịch bản: role ADMINISTRATOR qua mọi endpoint nhưng xóa server vẫn chỉ của chủ
  // Input: member giữ role ADMINISTRATOR gọi từng endpoint rồi DELETE server
  // Output: mọi endpoint trả mã thành công; DELETE server 403 và server còn nguyên
  @Test
  void administratorPassesEverywhereButNotDeleteServer() throws Exception {
    for (Endpoint endpoint : Endpoint.values()) {
      Fixture fx = fixture();
      grantRole(fx, ADMINISTRATOR);

      HttpResponse<String> response = invoke(endpoint, fx, fx.member);

      assertThat(response.statusCode()).as(endpoint.name()).isEqualTo(endpoint.success);
    }
    Fixture fx = fixture();
    grantRole(fx, ADMINISTRATOR);

    assertThat(call("DELETE", "/servers/" + fx.server, fx.member, null).statusCode())
        .isEqualTo(403);
    assertThat(call("GET", "/servers/" + fx.server, fx.owner, null).statusCode()).isEqualTo(200);
  }

  // Kịch bản: người ngoài server nhận 404 ở mọi endpoint
  // Input: user lạ gọi từng endpoint trên server có thật
  // Output: mọi endpoint 404 NOT_FOUND
  @Test
  void strangerGets404Everywhere() throws Exception {
    for (Endpoint endpoint : Endpoint.values()) {
      Fixture fx = fixture();

      HttpResponse<String> response = invoke(endpoint, fx, UUID.randomUUID());

      assertThat(response.statusCode()).as(endpoint.name()).isEqualTo(404);
      assertThat(code(response)).as(endpoint.name()).isEqualTo("NOT_FOUND");
    }
  }

  // Kịch bản: gỡ CREATE_INVITE khỏi @everyone thì member thường mất quyền tạo invite
  // Input: owner PATCH @everyone permissions chỉ còn VIEW_CHANNEL, member POST /invites
  // Output: PATCH 200, POST invite 403 PERMISSION_DENIED
  @Test
  void removingCreateInviteFromEveryoneBlocksPlainMember() throws Exception {
    Fixture fx = fixture();
    assertThat(invoke(Endpoint.CREATE_INVITE_AT, fx, fx.member).statusCode()).isEqualTo(201);

    HttpResponse<String> patch =
        call(
            "PATCH",
            "/servers/" + fx.server + "/roles/" + everyoneId(fx),
            fx.owner,
            "{\"permissions\":" + VIEW_CHANNEL + "}");
    HttpResponse<String> response = invoke(Endpoint.CREATE_INVITE_AT, fx, fx.member);

    assertThat(patch.statusCode()).isEqualTo(200);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("PERMISSION_DENIED");
  }

  // Kịch bản: cấp MANAGE_ROLES cho @everyone vẫn không cho member thường tạo role
  // Input: owner PATCH @everyone thêm MANAGE_ROLES, member chỉ có @everyone POST /roles
  // Output: PATCH 200, POST role 403 vì vị trí cao nhất của member là 0 và không có chỗ đặt role
  @Test
  void manageRolesOnEveryoneStillCannotCreateRole() throws Exception {
    Fixture fx = fixture();

    HttpResponse<String> patch =
        call(
            "PATCH",
            "/servers/" + fx.server + "/roles/" + everyoneId(fx),
            fx.owner,
            "{\"permissions\":" + (EVERYONE_DEFAULT | MANAGE_ROLES) + "}");
    HttpResponse<String> response =
        call("POST", "/servers/" + fx.server + "/roles", fx.member, "{\"name\":\"Mine\"}");

    assertThat(patch.statusCode()).isEqualTo(200);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("PERMISSION_DENIED");
  }

  // Kịch bản: hai PATCH song song cùng kênh đọc cùng phiên bản
  // Input: hai luồng PATCH tên khác nhau lên cùng kênh, cùng chờ nhau trước khi lưu
  // Output: đúng một 200 và một 409 VERSION_CONFLICT
  @Test
  void parallelChannelPatchYieldsExactlyOneConflict() throws Exception {
    Fixture fx = fixture();
    String channel = firstChannel(fx);
    CyclicBarrier bothRead = new CyclicBarrier(2);
    doAnswer(
            invocation -> {
              bothRead.await(10, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .when(channelRepository)
        .save(any(Channel.class));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (String name : new String[] {"alpha", "beta"}) {
        futures.add(
            pool.submit(
                () ->
                    call(
                        "PATCH",
                        "/servers/" + fx.server + "/channels/" + channel,
                        fx.owner,
                        "{\"name\":\"" + name + "\"}")));
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
      assertThat(code(conflict)).isEqualTo("VERSION_CONFLICT");
    } finally {
      pool.shutdownNow();
    }
  }

  // Gọi endpoint bằng user cho trước, tài nguyên cần có do chủ server tạo mới ngay trước đó
  private HttpResponse<String> invoke(Endpoint endpoint, Fixture fx, UUID user) throws Exception {
    String base = "/servers/" + fx.server;
    return switch (endpoint) {
      case RENAME_SERVER -> call("PATCH", base, user, "{\"name\":\"Renamed\"}");
      case CREATE_CATEGORY -> call("POST", base + "/categories", user, "{\"name\":\"Cat\"}");
      case UPDATE_CATEGORY ->
          call("PATCH", base + "/categories/" + newCategory(fx), user, "{\"name\":\"Cat2\"}");
      case DELETE_CATEGORY -> call("DELETE", base + "/categories/" + newCategory(fx), user, null);
      case CREATE_CHANNEL -> call("POST", base + "/channels", user, "{\"name\":\"chat\"}");
      case UPDATE_CHANNEL ->
          call("PATCH", base + "/channels/" + newChannel(fx), user, "{\"topic\":\"t\"}");
      case DELETE_CHANNEL -> call("DELETE", base + "/channels/" + newChannel(fx), user, null);
      case LIST_INVITES -> call("GET", base + "/invites", user, null);
      case CREATE_INVITE_AT -> call("POST", base + "/invites", user, "{}");
      case REVOKE_INVITE -> call("DELETE", "/invites/" + newInvite(fx), user, null);
    };
  }

  // Dựng server của chủ cùng một thành viên thường
  private Fixture fixture() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    HttpResponse<String> response = call("POST", "/servers", owner, "{\"name\":\"Perm\"}");
    String server = MAPPER.readTree(response.body()).get("id").asText();
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at) values (?::uuid, ?, now())",
        server,
        member);
    return new Fixture(owner, member, server);
  }

  // Chủ tạo role có đúng mặt nạ quyền rồi gán cho thành viên qua REST
  private void grantRole(Fixture fx, long permissions) throws Exception {
    HttpResponse<String> created =
        call(
            "POST",
            "/servers/" + fx.server + "/roles",
            fx.owner,
            "{\"name\":\"Granted\",\"permissions\":" + permissions + "}");
    assertThat(created.statusCode()).isEqualTo(201);
    String role = MAPPER.readTree(created.body()).get("id").asText();
    HttpResponse<String> assigned =
        call(
            "PUT",
            "/servers/" + fx.server + "/members/" + fx.member + "/roles",
            fx.owner,
            "{\"roleIds\":[\"" + role + "\"]}");
    assertThat(assigned.statusCode()).isEqualTo(200);
  }

  // Lấy id role @everyone của server
  private String everyoneId(Fixture fx) throws Exception {
    JsonNode roles =
        MAPPER.readTree(call("GET", "/servers/" + fx.server + "/roles", fx.owner, null).body());
    return roles.get(0).get("id").asText();
  }

  // Chủ tạo category mới và trả id
  private String newCategory(Fixture fx) throws Exception {
    HttpResponse<String> r =
        call("POST", "/servers/" + fx.server + "/categories", fx.owner, "{\"name\":\"Tmp\"}");
    assertThat(r.statusCode()).isEqualTo(201);
    return MAPPER.readTree(r.body()).get("id").asText();
  }

  // Chủ tạo kênh mới và trả id
  private String newChannel(Fixture fx) throws Exception {
    HttpResponse<String> r =
        call("POST", "/servers/" + fx.server + "/channels", fx.owner, "{\"name\":\"tmp\"}");
    assertThat(r.statusCode()).isEqualTo(201);
    return MAPPER.readTree(r.body()).get("id").asText();
  }

  // Chủ tạo lời mời mới và trả mã
  private String newInvite(Fixture fx) throws Exception {
    HttpResponse<String> r = call("POST", "/servers/" + fx.server + "/invites", fx.owner, "{}");
    assertThat(r.statusCode()).isEqualTo(201);
    return MAPPER.readTree(r.body()).get("code").asText();
  }

  // Lấy id kênh mặc định đầu tiên trong chi tiết server
  private String firstChannel(Fixture fx) throws Exception {
    return MAPPER
        .readTree(call("GET", "/servers/" + fx.server, fx.owner, null).body())
        .get("channels")
        .get(0)
        .get("id")
        .asText();
  }

  // Đọc trường code của thân lỗi
  private static String code(HttpResponse<String> response) throws Exception {
    return MAPPER.readTree(response.body()).get("code").asText();
  }

  // Gọi method trên /api/v1{path} với Bearer của user, body null là không gửi body
  private HttpResponse<String> call(String method, String path, UUID user, String json)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
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
