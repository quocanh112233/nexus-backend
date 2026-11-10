package com.nexus.messaging.infrastructure.grpc;

import com.nexus.messaging.application.port.UserDirectory;
import io.grpc.ManagedChannel;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Dựng kênh gRPC và UserDirectory tới Identity, dùng lại cấu hình kênh của Community
@Configuration
class IdentityChannelConfig {

  // Kênh tới Identity cùng service token với Community, đóng khi context tắt
  @Bean(destroyMethod = "shutdownNow")
  ManagedChannel identityChannel(
      @Value("${app.grpc.identity.target}") String target,
      @Value("${app.grpc.service-token:}") String serviceToken,
      ObservationRegistry registry) {
    return CommunityChannelConfig.newChannel(target, registry, serviceToken);
  }

  // UserDirectory gọi Identity với deadline theo app.grpc.identity.deadline
  @Bean
  UserDirectory userDirectory(
      @Qualifier("identityChannel") ManagedChannel identityChannel,
      @Value("${app.grpc.identity.deadline:300ms}") Duration deadline) {
    return new GrpcUserDirectory(identityChannel, deadline);
  }
}
