package com.nexus.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

// Kiểm tra auto-configuration của nexus-common-web được nạp qua file imports
class NexusCommonWebAutoConfigurationTest {

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(NexusCommonWebAutoConfiguration.class));

  // Kịch bản: service servlet chỉ thêm dependency
  // Input: context web chứa auto-configuration
  // Output: có bean GlobalExceptionHandler và RequestIdFilter đăng ký với order cao nhất
  @Test
  void registersHandlerAndHighestOrderFilter() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasSingleBean(GlobalExceptionHandler.class);
          assertThat(ctx.getBean(FilterRegistrationBean.class).getOrder())
              .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        });
  }

  // Kịch bản: file imports khai báo đúng class auto-configuration
  // Input: đọc META-INF/spring/...AutoConfiguration.imports trên classpath
  // Output: chứa NexusCommonWebAutoConfiguration
  @Test
  void importsFileListsAutoConfiguration() throws Exception {
    var in =
        getClass()
            .getResourceAsStream(
                "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
    assertThat(new String(in.readAllBytes()))
        .contains(NexusCommonWebAutoConfiguration.class.getName());
  }
}
