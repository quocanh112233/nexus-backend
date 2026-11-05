package com.nexus.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.IdentityIntegrationTest;
import com.nexus.identity.application.port.DomainEventPublisher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// Chứng minh user và dòng outbox cùng commit hoặc cùng rollback trong transaction của use case
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RegisterAtomicityIT.FailingPublisherConfig.class)
class RegisterAtomicityIT extends IdentityIntegrationTest {

  private static final AtomicBoolean FAIL_AFTER_WRITE = new AtomicBoolean();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // Bọc publisher thật: ghi outbox xong mới ném lỗi khi cờ FAIL_AFTER_WRITE bật
  @TestConfiguration
  static class FailingPublisherConfig {

    // Hậu xử lý bean DomainEventPublisher thành bản bọc ném lỗi sau khi ghi outbox
    @Bean
    static BeanPostProcessor failingPublisher() {
      return new BeanPostProcessor() {
        // Bọc DomainEventPublisher thành bản ném lỗi sau khi ghi outbox, bean khác giữ nguyên
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (!(bean instanceof DomainEventPublisher real)) {
            return bean;
          }
          return (DomainEventPublisher)
              event -> {
                real.publish(event);
                if (FAIL_AFTER_WRITE.get()) {
                  throw new IllegalStateException("boom-after-outbox");
                }
              };
        }
      };
    }
  }

  // Đặt lại cờ về chế độ không lỗi trước mỗi test
  @BeforeEach
  void resetFlag() {
    FAIL_AFTER_WRITE.set(false);
  }

  // Kịch bản: lỗi sau khi outbox đã insert thì cả user lẫn dòng outbox đều rollback
  // Input: đăng ký hợp lệ khi publisher ghi outbox rồi ném IllegalStateException
  // Output: 500 INTERNAL_ERROR không lộ message, không có user, số dòng outbox không đổi
  @Test
  void failureAfterOutboxWriteRollsBackUserAndOutbox() throws Exception {
    String tag = tag();
    int outboxBefore = outboxCount();
    FAIL_AFTER_WRITE.set(true);

    HttpResponse<String> response = register(tag);

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.body()).contains("INTERNAL_ERROR").doesNotContain("boom-after-outbox");
    assertThat(userCount(tag)).isZero();
    assertThat(outboxCount()).isEqualTo(outboxBefore);
  }

  // Kịch bản: đối chứng, không ném lỗi thì cả user lẫn dòng outbox đều được commit
  // Input: đăng ký hợp lệ khi publisher chỉ ghi outbox
  // Output: 201, có 1 dòng users và số dòng outbox_event tăng đúng 1
  @Test
  void withoutFailureUserAndOutboxBothCommit() throws Exception {
    String tag = tag();
    int outboxBefore = outboxCount();

    HttpResponse<String> response = register(tag);

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(userCount(tag)).isEqualTo(1);
    assertThat(outboxCount()).isEqualTo(outboxBefore + 1);
  }

  // Sinh chuỗi ngẫu nhiên hợp lệ cho username và phần đầu email
  private static String tag() {
    return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  // Đếm số dòng outbox user.registered hiện có
  private int outboxCount() {
    return jdbc.queryForObject(
        "select count(*) from identity.outbox_event where routing_key = 'user.registered'",
        Integer.class);
  }

  // Đếm số dòng users theo username
  private int userCount(String username) {
    return jdbc.queryForObject(
        "select count(*) from identity.users where username = ?", Integer.class, username);
  }

  // Gọi POST /api/v1/auth/register với user hợp lệ ngẫu nhiên
  private HttpResponse<String> register(String tag) throws Exception {
    String json =
        "{\"email\":\""
            + tag
            + "@x.io\",\"username\":\""
            + tag
            + "\",\"password\":\"secret-pass-1\"}";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
