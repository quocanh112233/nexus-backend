package com.nexus.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

// Tự đăng ký GlobalExceptionHandler và RequestIdFilter cho service servlet khi thêm dependency
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class NexusCommonWebAutoConfiguration {

  // Đăng ký advice xử lý lỗi chung
  @Bean
  GlobalExceptionHandler globalExceptionHandler() {
    return new GlobalExceptionHandler();
  }

  // Đăng ký RequestIdFilter với thứ tự cao nhất
  @Bean
  FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
    FilterRegistrationBean<RequestIdFilter> registration =
        new FilterRegistrationBean<>(new RequestIdFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
