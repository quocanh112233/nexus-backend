package com.nexus.messaging.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

// Kiểm tra MessagingExceptionHandler ghi log khi đổi lỗi phụ thuộc sang 503
class MessagingExceptionHandlerTest {

  // Kịch bản: Community không trả lời nên handler đổi sang 503
  // Input: DependencyUnavailableException("down") có nguyên nhân gốc chứa chuỗi bí mật
  // Output: trả 503, đúng một log WARN chứa tên lớp lỗi và "down", không stack, không chuỗi bí mật
  @Test
  void dependencyDownIsLoggedAsWarnWithoutStackOrCause() {
    Logger logger = (Logger) LoggerFactory.getLogger(MessagingExceptionHandler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      var response =
          new MessagingExceptionHandler()
              .handleDependencyDown(
                  new DependencyUnavailableException("down", new RuntimeException("s3cr3t")),
                  new MockHttpServletRequest());

      assertThat(response.getStatusCode().value()).isEqualTo(503);
      assertThat(appender.list).hasSize(1);
      ILoggingEvent event = appender.list.get(0);
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage())
          .contains("DependencyUnavailableException", "down")
          .doesNotContain("s3cr3t");
      assertThat(event.getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
    }
  }
}
