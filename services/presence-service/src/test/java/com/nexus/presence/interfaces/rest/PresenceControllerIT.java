package com.nexus.presence.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.presence.domain.relation.RelationshipProjection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra GET /api/v1/presence với JWT thật, Redis thật và quan hệ ghi thẳng vào projection
class PresenceControllerIT extends PresenceApiIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private RelationshipProjection projection;
  @Autowired private StringRedisTemplate redis;
  @LocalManagementPort private int managementPort;

  // Kịch bản: viewer chung server với một user đang ONLINE và một user không có status
  // Input: GET userIds=online,offline (dạng phẩy) bởi viewer
  // Output: 200, đúng 2 phần tử ONLINE và OFFLINE
  @Test
  void returnsOnlineAndOfflineForVisibleUsers() throws Exception {
    UUID viewer = UUID.randomUUID();
    UUID online = UUID.randomUUID();
    UUID offline = UUID.randomUUID();
    UUID server = UUID.randomUUID();
    Set.of(viewer, online, offline)
        .forEach(u -> projection.load(u, Set.of(server), Set.of(), projection.revision(u)));
    redis.opsForValue().set("presence:status:" + online, "ONLINE");

    HttpResponse<String> response =
        getPresence("?userIds=" + online + "," + offline, bearer(viewer));

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body).hasSize(2);
    assertThat(body.get(0).get("userId").asText()).isEqualTo(online.toString());
    assertThat(body.get(0).get("status").asText()).isEqualTo("ONLINE");
    assertThat(body.get(1).get("userId").asText()).isEqualTo(offline.toString());
    assertThat(body.get(1).get("status").asText()).isEqualTo("OFFLINE");
  }

  // Kịch bản: đúng 200 id hợp lệ qua, 201 id bị từ chối
  // Input: GET với 200 UUID rồi với 201 UUID
  // Output: 200 rồi 400 VALIDATION_FAILED
  @Test
  void limitOf200Enforced() throws Exception {
    UUID viewer = UUID.randomUUID();
    projection.load(viewer, Set.of(), Set.of(), projection.revision(viewer));

    HttpResponse<String> ok = getPresence(ids(200), bearer(viewer));
    HttpResponse<String> tooMany = getPresence(ids(201), bearer(viewer));

    assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
    assertThat(tooMany.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(tooMany.body()).get("code").asText()).isEqualTo("VALIDATION_FAILED");
  }

  // Kịch bản: một id không phải UUID
  // Input: GET userIds=not-a-uuid
  // Output: 400 VALIDATION_FAILED
  @Test
  void invalidUuidIs400() throws Exception {
    HttpResponse<String> response = getPresence("?userIds=not-a-uuid", bearer(UUID.randomUUID()));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(response.body()).get("code").asText())
        .isEqualTo("VALIDATION_FAILED");
  }

  // Kịch bản: thiếu hoặc rỗng userIds
  // Input: GET không tham số và GET userIds=
  // Output: cả hai 200 với mảng rỗng
  @Test
  void emptyIdsIs200EmptyList() throws Exception {
    String token = bearer(UUID.randomUUID());

    HttpResponse<String> missing = getPresence("", token);
    HttpResponse<String> blank = getPresence("?userIds=", token);

    assertThat(missing.statusCode()).isEqualTo(200);
    assertThat(missing.body()).isEqualTo("[]");
    assertThat(blank.statusCode()).isEqualTo(200);
    assertThat(blank.body()).isEqualTo("[]");
  }

  // Kịch bản: cùng một id lặp lại, cả dạng phẩy lẫn tham số lặp
  // Input: GET userIds=x,x&userIds=x bởi chính x
  // Output: đúng 1 phần tử
  @Test
  void duplicateIdsAreMerged() throws Exception {
    UUID viewer = UUID.randomUUID();
    projection.load(viewer, Set.of(), Set.of(), projection.revision(viewer));

    HttpResponse<String> response =
        getPresence("?userIds=" + viewer + "," + viewer + "&userIds=" + viewer, bearer(viewer));

    assertThat(MAPPER.readTree(response.body())).hasSize(1);
  }

  // Kịch bản: không có token hoặc token rác
  // Input: GET không Authorization rồi với Bearer rác
  // Output: cả hai 401 UNAUTHENTICATED
  @Test
  void noTokenIs401() throws Exception {
    HttpResponse<String> none = getPresence("", null);
    HttpResponse<String> garbage = getPresence("", "Bearer garbage");

    assertThat(none.statusCode()).isEqualTo(401);
    assertThat(MAPPER.readTree(none.body()).get("code").asText()).isEqualTo("UNAUTHENTICATED");
    assertThat(garbage.statusCode()).isEqualTo(401);
  }

  // Kịch bản: healthcheck của Docker gọi cổng management không kèm token
  // Input: GET /actuator/health/readiness trên cổng management
  // Output: 200
  @Test
  void readinessProbeStaysOpen() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/health/readiness"))
            .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
  }

  // Query userIds gồm n UUID ngẫu nhiên phân tách bằng dấu phẩy
  private static String ids(int n) {
    return "?userIds="
        + IntStream.range(0, n)
            .mapToObj(i -> UUID.randomUUID().toString())
            .collect(Collectors.joining(","));
  }
}
