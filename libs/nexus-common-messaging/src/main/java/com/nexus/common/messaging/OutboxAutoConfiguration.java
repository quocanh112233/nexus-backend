package com.nexus.common.messaging;

import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

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

  // Bean ghi outbox dùng chung transaction của use case
  @Bean
  @ConditionalOnMissingBean
  OutboxWriter outboxWriter(OutboxEventDao dao, Clock clock) {
    return new OutboxWriter(dao, clock);
  }
}
