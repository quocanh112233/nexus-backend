package com.nexus.common.messaging;

import io.micrometer.tracing.Tracer;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Đưa outbox vào phạm vi quét JPA của service và đăng ký OutboxWriter
@AutoConfiguration(
    beforeName = {
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
      "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
    })
@AutoConfigurationPackage(basePackageClasses = OutboxWriter.class)
public class OutboxAutoConfiguration {

  // Clock UTC dự phòng khi service chưa khai báo Clock
  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock clock() {
    return Clock.systemUTC();
  }

  // Supplier dự phòng khi không có tracing: không gắn traceparent
  @Bean
  @ConditionalOnMissingBean
  TraceparentSupplier noTraceparent() {
    return () -> null;
  }

  // Bean ghi outbox dùng chung transaction của use case
  @Bean
  @ConditionalOnMissingBean
  OutboxWriter outboxWriter(OutboxEventDao dao, Clock clock, TraceparentSupplier traceparent) {
    return new OutboxWriter(dao, clock, traceparent);
  }

  // Lấy traceparent từ Tracer của Micrometer khi thư viện tracing có trên classpath
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(Tracer.class)
  static class TracingConfiguration {

    // Dựng 00-{traceId}-{spanId}-{01|00} từ span hiện tại, null khi không có Tracer hoặc span
    @Bean
    @ConditionalOnMissingBean
    TraceparentSupplier tracerTraceparent(ObjectProvider<Tracer> tracers) {
      return () -> {
        Tracer tracer = tracers.getIfAvailable();
        var span = tracer == null ? null : tracer.currentSpan();
        if (span == null) {
          return null;
        }
        var ctx = span.context();
        return "00-%s-%s-%s"
            .formatted(
                ctx.traceId(), ctx.spanId(), Boolean.TRUE.equals(ctx.sampled()) ? "01" : "00");
      };
    }
  }
}
