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

// Kiểm tra POST, PATCH, DELETE kênh với phân quyền chủ server, kiểm tra đầu vào và outbox
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChannelIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // Kịch bản: tạo kênh trong category kèm topic và outbox chỉ ghi channel.created
  // Input: owner POST {name, topic, categoryId, position:5} vào category mặc định
  // Output: 201 đủ trường, Location, outbox channel.created một dòng, không có channel.updated
  @Test
  void createsChannelInCategory() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String category = firstCategory(server, owner);

    HttpResponse<String> response =
        call(
            "POST",
            server + "/channels",
            owner,
            "{\"name\":\"dev-talk\",\"topic\":\"code\",\"categoryId\":\""
                + category
                + "\",\"position\":5}");

    JsonNode c = MAPPER.readTree(response.body());
    String id = c.get("id").asText();
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.headers().firstValue("Location"))
        .contains("/api/v1/servers/" + server + "/channels/" + id);
    assertThat(c.get("serverId").asText()).isEqualTo(server);
    assertThat(c.get("categoryId").asText()).isEqualTo(category);
    assertThat(c.get("name").asText()).isEqualTo("dev-talk");
    assertThat(c.get("topic").asText()).isEqualTo("code");
    assertThat(c.get("position").asInt()).isEqualTo(5);
    List<String> created = outbox("channel.created", id);
    assertThat(created).hasSize(1);
    assertThat(MAPPER.readTree(created.get(0)).get("categoryId").asText()).isEqualTo(category);
    assertThat(outbox("channel.updated", id)).isEmpty();
  }

  // Kịch bản: tên kênh viết hoa bị từ chối
  // Input: owner POST name "General"
  // Output: 400 VALIDATION_FAILED
  @Test
  void uppercaseNameReturns400() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);

    assertBadRequest(call("POST", server + "/channels", owner, "{\"name\":\"General\"}"));
  }

  // Kịch bản: tên kênh có khoảng trắng hoặc rỗng bị từ chối, PATCH cũng vậy
  // Input: owner POST name "a b", "" và PATCH name "a b" lên kênh general
  // Output: cả ba 400 VALIDATION_FAILED
  @Test
  void nameWithSpaceReturns400() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String general = firstChannel(server, owner);

    assertBadRequest(call("POST", server + "/channels", owner, "{\"name\":\"a b\"}"));
    assertBadRequest(call("POST", server + "/channels", owner, "{\"name\":\"\"}"));
    assertBadRequest(call("PATCH", server + "/channels/" + general, owner, "{\"name\":\"a b\"}"));
  }

  // Kịch bản: tên kênh dài 101 ký tự bị từ chối, 100 ký tự được nhận
  // Input: owner POST name 101 chữ "a" rồi 100 chữ "a"
  // Output: 400 VALIDATION_FAILED rồi 201
  @Test
  void name101CharsReturns400() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);

    assertBadRequest(
        call("POST", server + "/channels", owner, "{\"name\":\"" + "a".repeat(101) + "\"}"));
    assertThat(
            call("POST", server + "/channels", owner, "{\"name\":\"" + "a".repeat(100) + "\"}")
                .statusCode())
        .isEqualTo(201);
  }

  // Kịch bản: topic dài hơn 1024 ký tự bị từ chối ở cả tạo và sửa, đúng 1024 thì nhận
  // Input: POST và PATCH topic 1025 chữ "t", rồi PATCH topic 1024 chữ "t"
  // Output: hai 400 VALIDATION_FAILED rồi 200
  @Test
  void topicOver1024Returns400() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String general = firstChannel(server, owner);
    String path = server + "/channels/" + general;

    assertBadRequest(
        call(
            "POST",
            server + "/channels",
            owner,
            "{\"name\":\"x\",\"topic\":\"" + "t".repeat(1025) + "\"}"));
    assertBadRequest(call("PATCH", path, owner, "{\"topic\":\"" + "t".repeat(1025) + "\"}"));
    assertThat(call("PATCH", path, owner, "{\"topic\":\"" + "t".repeat(1024) + "\"}").statusCode())
        .isEqualTo(200);
  }

  // Kịch bản: chuyển kênh giữa các category và về không category, vắng categoryId thì giữ nguyên
  // Input: PATCH categoryId=B, PATCH chỉ đổi name, PATCH categoryId=null, PATCH chỉ đổi topic
  // Output: categoryId lần lượt B, B, null (JSON null), null; GET xác nhận lần cuối
  @Test
  void moveChannelBetweenCategoriesAndToNone() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String general = firstChannel(server, owner);
    String categoryB = createCategory(server, owner, "B");
    String path = server + "/channels/" + general;

    JsonNode moved =
        MAPPER.readTree(
            call("PATCH", path, owner, "{\"categoryId\":\"" + categoryB + "\"}").body());
    assertThat(moved.get("categoryId").asText()).isEqualTo(categoryB);
    JsonNode renamed = MAPPER.readTree(call("PATCH", path, owner, "{\"name\":\"lobby\"}").body());
    assertThat(renamed.get("categoryId").asText()).isEqualTo(categoryB);
    assertThat(renamed.get("name").asText()).isEqualTo("lobby");
    HttpResponse<String> detached = call("PATCH", path, owner, "{\"categoryId\":null}");
    assertThat(detached.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(detached.body()).get("categoryId").isNull()).isTrue();
    JsonNode topic = MAPPER.readTree(call("PATCH", path, owner, "{\"topic\":\"t\"}").body());
    assertThat(topic.get("categoryId").isNull()).isTrue();

    JsonNode channel = channelOf(server, owner, general);
    assertThat(channel.get("categoryId").isNull()).isTrue();
    assertThat(channel.get("name").asText()).isEqualTo("lobby");
    assertThat(outbox("channel.updated", general)).hasSize(4);
  }

  // Kịch bản: sửa nhiều trường một lần chỉ phát một channel.updated, topic rỗng xóa topic
  // Input: PATCH name+topic+position+categoryId=null, rồi PATCH {} , rồi PATCH topic ""
  // Output: 200 giá trị mới, một updated; {} không thêm event; topic "" thành rỗng, thêm event
  @Test
  void updateWithManyFieldsEmitsOneChannelUpdated() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String general = firstChannel(server, owner);
    String path = server + "/channels/" + general;

    HttpResponse<String> response =
        call(
            "PATCH",
            path,
            owner,
            "{\"name\":\"news\",\"topic\":\"hot\",\"position\":9,\"categoryId\":null}");

    JsonNode c = MAPPER.readTree(response.body());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(c.get("name").asText()).isEqualTo("news");
    assertThat(c.get("topic").asText()).isEqualTo("hot");
    assertThat(c.get("position").asInt()).isEqualTo(9);
    assertThat(c.get("categoryId").isNull()).isTrue();
    List<String> updated = outbox("channel.updated", general);
    assertThat(updated).hasSize(1);
    JsonNode payload = MAPPER.readTree(updated.get(0));
    assertThat(payload.get("name").asText()).isEqualTo("news");
    assertThat(payload.get("topic").asText()).isEqualTo("hot");
    assertThat(payload.get("position").asInt()).isEqualTo(9);
    assertThat(payload.get("categoryId").isNull()).isTrue();

    assertThat(call("PATCH", path, owner, "{}").statusCode()).isEqualTo(200);
    assertThat(outbox("channel.updated", general)).hasSize(1);

    JsonNode cleared = MAPPER.readTree(call("PATCH", path, owner, "{\"topic\":\"\"}").body());
    assertThat(cleared.get("topic").asText()).isEmpty();
    assertThat(outbox("channel.updated", general)).hasSize(2);
  }

  // Kịch bản: xóa category giữ lại kênh con với categoryId null và chỉ phát category.deleted
  // Input: owner DELETE category mặc định chứa kênh general
  // Output: 204, kênh general còn với categoryId null, outbox category.deleted 1, channel.deleted 0
  @Test
  void deleteCategoryKeepsChannelsWithNullCategory() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String category = firstCategory(server, owner);
    String general = firstChannel(server, owner);

    HttpResponse<String> response = call("DELETE", server + "/categories/" + category, owner, null);

    assertThat(response.statusCode()).isEqualTo(204);
    JsonNode channel = channelOf(server, owner, general);
    assertThat(channel.get("categoryId").isNull()).isTrue();
    assertThat(MAPPER.readTree(call("GET", server, owner, null).body()).get("categories"))
        .isEmpty();
    assertThat(outbox("category.deleted", category)).hasSize(1);
    assertThat(outbox("channel.deleted", general)).isEmpty();
  }

  // Kịch bản: categoryId thuộc server khác hoặc không tồn tại bị từ chối với 404, sai UUID là 400
  // Input: POST và PATCH kênh với categoryId của server B, UUID lạ; categoryId "nope"
  // Output: 404 NOT_FOUND cho các trường hợp đầu, 400 VALIDATION_FAILED cho "nope" và số 5
  @Test
  void categoryFromOtherServerReturns404() throws Exception {
    UUID owner = UUID.randomUUID();
    String serverA = createServer(owner);
    String serverB = createServer(owner);
    String foreign = firstCategory(serverB, owner);
    String general = firstChannel(serverA, owner);

    for (String category : new String[] {foreign, UUID.randomUUID().toString()}) {
      String body = "\"categoryId\":\"" + category + "\"";
      HttpResponse<String> post =
          call("POST", serverA + "/channels", owner, "{\"name\":\"x\"," + body + "}");
      HttpResponse<String> patch =
          call("PATCH", serverA + "/channels/" + general, owner, "{" + body + "}");
      assertThat(post.statusCode()).isEqualTo(404);
      assertThat(patch.statusCode()).isEqualTo(404);
      assertThat(MAPPER.readTree(patch.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    }
    assertBadRequest(
        call("POST", serverA + "/channels", owner, "{\"name\":\"x\",\"categoryId\":\"nope\"}"));
    assertBadRequest(
        call("PATCH", serverA + "/channels/" + general, owner, "{\"categoryId\":\"nope\"}"));
    assertBadRequest(call("PATCH", serverA + "/channels/" + general, owner, "{\"categoryId\":5}"));
    assertThat(channelOf(serverA, owner, general).get("categoryId").asText())
        .isEqualTo(firstCategory(serverA, owner));
  }

  // Kịch bản: kênh của server khác hoặc không tồn tại trả 404 ở PATCH và DELETE
  // Input: PATCH, DELETE kênh của server B qua đường dẫn server A, và một UUID lạ
  // Output: tất cả 404 NOT_FOUND, kênh của server B còn nguyên
  @Test
  void channelOfOtherServerReturns404() throws Exception {
    UUID owner = UUID.randomUUID();
    String serverA = createServer(owner);
    String serverB = createServer(owner);
    String foreign = firstChannel(serverB, owner);

    for (String id : new String[] {foreign, UUID.randomUUID().toString()}) {
      String path = serverA + "/channels/" + id;
      HttpResponse<String> patch = call("PATCH", path, owner, "{\"name\":\"zzz\"}");
      assertThat(patch.statusCode()).isEqualTo(404);
      assertThat(MAPPER.readTree(patch.body()).get("code").asText()).isEqualTo("NOT_FOUND");
      assertThat(call("DELETE", path, owner, null).statusCode()).isEqualTo(404);
    }
    assertThat(channelOf(serverB, owner, foreign).get("name").asText()).isEqualTo("general");
  }

  // Kịch bản: channelId không phải UUID bị từ chối ở biên REST
  // Input: owner PATCH và DELETE /channels/not-a-uuid
  // Output: cả hai 400 VALIDATION_FAILED
  @Test
  void nonUuidChannelIdReturns400() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);

    assertBadRequest(call("PATCH", server + "/channels/not-a-uuid", owner, "{\"name\":\"x\"}"));
    assertBadRequest(call("DELETE", server + "/channels/not-a-uuid", owner, null));
  }

  // Kịch bản: vị trí mặc định là số anh em cùng nhóm, nhóm "không category" đếm riêng
  // Input: POST ba kênh vào category mặc định, rồi hai kênh không category, rồi position âm
  // Output: vị trí 1, 2, 3 trong category; 0, 1 ngoài category; position -1 là 400
  @Test
  void defaultPositionIsAppended() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String category = firstCategory(server, owner);

    for (int expected = 1; expected <= 3; expected++) {
      JsonNode c =
          MAPPER.readTree(
              call(
                      "POST",
                      server + "/channels",
                      owner,
                      "{\"name\":\"c" + expected + "\",\"categoryId\":\"" + category + "\"}")
                  .body());
      assertThat(c.get("position").asInt()).isEqualTo(expected);
    }
    for (int expected = 0; expected <= 1; expected++) {
      JsonNode c =
          MAPPER.readTree(
              call("POST", server + "/channels", owner, "{\"name\":\"n" + expected + "\"}").body());
      assertThat(c.get("categoryId").isNull()).isTrue();
      assertThat(c.get("position").asInt()).isEqualTo(expected);
    }
    assertBadRequest(
        call("POST", server + "/channels", owner, "{\"name\":\"neg\",\"position\":-1}"));
  }

  // Kịch bản: chủ server xóa kênh và outbox ghi channel.deleted
  // Input: owner DELETE kênh vừa tạo
  // Output: 204, kênh biến mất khỏi GET server, outbox channel.deleted một dòng
  @Test
  void ownerDeletesChannel() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);
    String id =
        MAPPER
            .readTree(call("POST", server + "/channels", owner, "{\"name\":\"tmp\"}").body())
            .get("id")
            .asText();

    assertThat(call("DELETE", server + "/channels/" + id, owner, null).statusCode()).isEqualTo(204);

    assertThat(MAPPER.readTree(call("GET", server, owner, null).body()).get("channels")).hasSize(1);
    assertThat(outbox("channel.deleted", id)).hasSize(1);
  }

  // Kịch bản: thành viên không phải chủ nhận 403, người ngoài nhận 404 ở cả POST, PATCH, DELETE
  // Input: user B là thành viên và user lạ thực hiện POST, PATCH, DELETE kênh của owner
  // Output: thành viên 403 PERMISSION_DENIED, người ngoài 404 NOT_FOUND, kênh không đổi
  @Test
  void nonOwnerIsRejected() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    String server = createServer(owner);
    addMember(server, member);
    String general = firstChannel(server, owner);
    String path = server + "/channels/" + general;

    for (UUID user : new UUID[] {member, stranger}) {
      int expected = user.equals(member) ? 403 : 404;
      assertThat(call("POST", server + "/channels", user, "{\"name\":\"x\"}").statusCode())
          .isEqualTo(expected);
      assertThat(call("PATCH", path, user, "{\"name\":\"x\"}").statusCode()).isEqualTo(expected);
      assertThat(call("DELETE", path, user, null).statusCode()).isEqualTo(expected);
    }
    assertThat(channelOf(server, owner, general).get("name").asText()).isEqualTo("general");
  }

  // Kiểm tra phản hồi là 400 VALIDATION_FAILED
  private static void assertBadRequest(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(response.body()).get("code").asText())
        .isEqualTo("VALIDATION_FAILED");
  }

  // Tạo server qua REST và trả id
  private String createServer(UUID owner) throws Exception {
    HttpResponse<String> response = call("POST", "", owner, "{\"name\":\"Chan\"}");
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Tạo category qua REST và trả id
  private String createCategory(String server, UUID owner, String name) throws Exception {
    return MAPPER
        .readTree(call("POST", server + "/categories", owner, "{\"name\":\"" + name + "\"}").body())
        .get("id")
        .asText();
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

  // Lấy id kênh general mặc định của server
  private String firstChannel(String server, UUID user) throws Exception {
    return MAPPER
        .readTree(call("GET", server, user, null).body())
        .get("channels")
        .get(0)
        .get("id")
        .asText();
  }

  // Tìm kênh theo id trong chi tiết server
  private JsonNode channelOf(String server, UUID user, String channelId) throws Exception {
    for (JsonNode c : MAPPER.readTree(call("GET", server, user, null).body()).get("channels")) {
      if (c.get("id").asText().equals(channelId)) {
        return c;
      }
    }
    throw new AssertionError("channel not found " + channelId);
  }

  // Thêm thành viên trực tiếp vào bảng members
  private void addMember(String serverId, UUID user) {
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at) values (?::uuid, ?, now())",
        serverId,
        user);
  }

  // Đọc payload outbox theo routing key, khớp id kênh hoặc id category, cũ trước mới sau
  private List<String> outbox(String routingKey, String id) {
    return jdbc.queryForList(
        "select payload::text from community.outbox_event where routing_key = ?"
            + " and (payload->>'channelId' = ? or payload->>'categoryId' = ?)"
            + " order by created_at",
        String.class,
        routingKey,
        id,
        id);
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
