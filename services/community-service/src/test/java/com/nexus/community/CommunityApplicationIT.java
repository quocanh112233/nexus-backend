package com.nexus.community;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;

// Khởi động community-service với Postgres thật và kiểm tra Flyway, health trên cổng quản trị
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class CommunityApplicationIT extends CommunityIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @LocalManagementPort private int managementPort;

  // Kịch bản: context khởi động, Flyway migrate V1 vào schema community và Hibernate validate đạt
  // Input: Postgres trống, spring.flyway.schemas=community, ddl-auto=validate
  // Output: đủ 9 bảng nghiệp vụ trong schema community và không có server_bans
  @Test
  void contextLoadsAndFlywayCreatesTables() {
    var tables =
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'community'",
            String.class);

    assertThat(tables)
        .contains(
            "servers",
            "roles",
            "categories",
            "channels",
            "channel_permission_overwrites",
            "members",
            "member_roles",
            "invites",
            "outbox_event")
        .doesNotContain("server_bans");
  }

  // Kịch bản: cổng quản trị phục vụ health không cần đăng nhập
  // Input: GET /actuator/health trên management port
  // Output: HTTP 200 và body có status UP
  @Test
  void managementPortServesHealth() throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/health"))
            .build();

    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }
}
