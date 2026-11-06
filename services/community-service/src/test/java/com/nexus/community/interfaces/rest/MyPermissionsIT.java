package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.server.PermissionSet;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra GET permissions/@me: owner có đủ bit, member có quyền mặc định, người ngoài 404
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MyPermissionsIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // Kịch bản: owner xem quyền của mình trong server vừa tạo
  // Input: GET permissions/@me của owner khi server chỉ có kênh general
  // Output: 200, server và quyền kênh general đều bằng mọi bit
  @Test
  void ownerSeesAllBits() throws Exception {
    UUID owner = UUID.randomUUID();
    String server = createServer(owner);

    HttpResponse<String> response = get(server, owner);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.get("server").asLong()).isEqualTo(PermissionSet.ALL.bits());
    assertThat(body.get("channels")).hasSize(1);
    assertThat(body.get("channels").get(0).get("channelId").asText()).isNotBlank();
    assertThat(body.get("channels").get(0).get("permissions").asLong())
        .isEqualTo(PermissionSet.ALL.bits());
  }

  // Kịch bản: member thường xem quyền khi chưa có overwrite nào
  // Input: GET permissions/@me của member thường
  // Output: 200, server và quyền kênh bằng quyền mặc định của @everyone
  @Test
  void plainMemberSeesEveryoneDefaults() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String server = createServer(owner);
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at) values (?::uuid, ?, now())",
        server,
        member);

    JsonNode body = MAPPER.readTree(get(server, member).body());

    long expected = PermissionSet.EVERYONE_DEFAULT.bits();
    assertThat(body.get("server").asLong()).isEqualTo(expected);
    assertThat(body.get("channels").get(0).get("permissions").asLong()).isEqualTo(expected);
  }

  // Kịch bản: người ngoài server và server không tồn tại
  // Input: người lạ GET permissions/@me của server có thật, rồi của server ngẫu nhiên
  // Output: cả hai 404 NOT_FOUND
  @Test
  void strangerAndUnknownServerGet404() throws Exception {
    String server = createServer(UUID.randomUUID());

    HttpResponse<String> stranger = get(server, UUID.randomUUID());
    HttpResponse<String> unknown = get(UUID.randomUUID().toString(), UUID.randomUUID());

    assertThat(stranger.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(stranger.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(unknown.statusCode()).isEqualTo(404);
  }

  // Tạo server tên "Mine" qua REST và trả id
  private String createServer(UUID owner) throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/servers"))
                    .header("Authorization", bearer(owner))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Mine\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // GET permissions/@me của server do user gọi
  private HttpResponse<String> get(String server, UUID user) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/v1/servers/"
                            + server
                            + "/permissions/@me"))
                .header("Authorization", bearer(user))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
