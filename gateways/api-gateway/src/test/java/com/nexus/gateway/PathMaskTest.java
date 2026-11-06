package com.nexus.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

// Kiểm tra PathMask và việc gateway không ghi mã invite vào log
class PathMaskTest {

  // Kịch bản: path accept invite và xem invite chứa mã thật
  // Input: /api/v1/invites/AbC123xY/accept, /api/v1/invites/AbC123xY, /api/v1/servers/1
  // Output: hai path đầu thành dạng /invites/{code}, path còn lại giữ nguyên
  @Test
  void masksInviteCodeOnly() {
    assertThat(PathMask.mask("/api/v1/invites/AbC123xY/accept"))
        .isEqualTo("/api/v1/invites/{code}/accept");
    assertThat(PathMask.mask("/api/v1/invites/AbC123xY")).isEqualTo("/api/v1/invites/{code}");
    assertThat(PathMask.mask("/api/v1/servers/1")).isEqualTo("/api/v1/servers/1");
  }

  // Kịch bản: log truy cập và log lỗi của gateway cho request mang mã invite
  // Input: POST /api/v1/invites/AbC123xY/accept qua RequestIdFilter và lỗi không biết qua handler
  // Output: không dòng log nào chứa AbC123xY, có path=/api/v1/invites/{code}/accept
  @Test
  void gatewayLogsDoNotContainInviteCode() {
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    Logger root = (Logger) LoggerFactory.getLogger("com.nexus.gateway");
    root.addAppender(logs);
    try {
      MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.post("/api/v1/invites/AbC123xY/accept").build());
      WebFilterChain chain = e -> Mono.empty();
      new RequestIdFilter().filter(exchange, chain).block();
      new GatewayErrorHandler()
          .handle(
              MockServerWebExchange.from(
                  MockServerHttpRequest.post("/api/v1/invites/AbC123xY/accept").build()),
              new IllegalStateException("boom"))
          .block();
    } finally {
      root.detachAppender(logs);
    }
    assertThat(logs.list).hasSize(2);
    assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("AbC123xY"));
    assertThat(logs.list)
        .anyMatch(e -> e.getFormattedMessage().contains("path=/api/v1/invites/{code}/accept"))
        .anyMatch(
            e ->
                e.getFormattedMessage()
                    .contains("Unhandled exception on /api/v1/invites/{code}/accept"));
  }
}
