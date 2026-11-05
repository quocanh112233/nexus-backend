package com.nexus.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.common.messaging.OutboxEventDao;
import com.nexus.common.messaging.OutboxWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

// Khởi động identity-service với Postgres thật và kiểm tra Flyway, outbox, readiness
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class IdentityApplicationIT extends IdentityIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationContext context;
  @LocalServerPort private int appPort;
  @LocalManagementPort private int managementPort;

  // Kịch bản: context khởi động, Flyway migrate vào schema identity và Hibernate validate đạt
  // Input: Postgres trống, spring.flyway.schemas=identity, ddl-auto=validate
  // Output: bảng schema identity đủ, có bean OutboxEventDao và OutboxWriter
  @Test
  void contextLoadsAndFlywayCreatesIdentitySchema() {
    var tables =
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'identity'",
            String.class);

    assertThat(tables).contains("flyway_schema_history", "users", "refresh_tokens", "outbox_event");
    assertThat(context.getBean(OutboxEventDao.class)).isNotNull();
    assertThat(context.getBean(OutboxWriter.class)).isNotNull();
  }

  // Kịch bản: tài liệu OpenAPI của springdoc truy cập được trên cổng ứng dụng
  // Input: GET /v3/api-docs trên cổng ứng dụng
  // Output: HTTP 200
  @Test
  void openApiDocsAreServed() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + appPort + "/v3/api-docs")).build();

    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
  }

  // Kịch bản: probe readiness trên cổng quản trị không cần đăng nhập
  // Input: GET /actuator/health/readiness trên management port
  // Output: HTTP 200 và body có status UP
  @Test
  void readinessProbeIsUp() throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/health/readiness"))
            .build();

    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }
}
