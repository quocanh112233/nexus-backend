package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.application.port.DomainEventPublisher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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

// Chứng minh server, role, category, kênh, thành viên và outbox cùng commit hoặc cùng rollback
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CreateServerAtomicityIT.FailingPublisherConfig.class)
class CreateServerAtomicityIT extends CommunityIntegrationTest {

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

  // Kịch bản: lỗi sau khi outbox đã insert thì toàn bộ dữ liệu của server rollback
  // Input: tạo server khi publisher ghi outbox rồi ném IllegalStateException
  // Output: 500 INTERNAL_ERROR và số dòng của sáu bảng liên quan không đổi
  @Test
  void failureAfterOutboxWriteRollsBackEverything() throws Exception {
    List<Integer> before = counts();
    FAIL_AFTER_WRITE.set(true);

    HttpResponse<String> response = create(UUID.randomUUID(), "Rollback");

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.body()).contains("INTERNAL_ERROR").doesNotContain("boom-after-outbox");
    assertThat(counts()).isEqualTo(before);
  }

  // Kịch bản: đối chứng, không ném lỗi thì mọi thứ được commit
  // Input: tạo server hợp lệ khi publisher chỉ ghi outbox
  // Output: 201 và mỗi bảng servers, roles, categories, channels, members tăng 1, outbox tăng 4
  @Test
  void withoutFailureEverythingCommits() throws Exception {
    List<Integer> before = counts();

    HttpResponse<String> response = create(UUID.randomUUID(), "Commit");

    assertThat(response.statusCode()).isEqualTo(201);
    List<Integer> after = counts();
    assertThat(IntStream.range(0, before.size()).mapToObj(i -> after.get(i) - before.get(i)))
        .containsExactly(1, 1, 1, 1, 1, 4);
  }

  // Đếm dòng các bảng servers, roles, categories, channels, members, outbox_event
  private List<Integer> counts() {
    return Stream.of("servers", "roles", "categories", "channels", "members", "outbox_event")
        .map(t -> jdbc.queryForObject("select count(*) from community." + t, Integer.class))
        .toList();
  }

  // Gọi POST /api/v1/servers với Bearer của owner
  private HttpResponse<String> create(UUID owner, String name) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/servers"))
            .header("Authorization", bearer(owner))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name + "\"}"))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
