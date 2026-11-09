package com.nexus.realtime.infrastructure.grpc;

import com.nexus.common.grpc.ClientMetadataInterceptor;
import com.nexus.realtime.application.subscription.AccessChecker;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Dựng kênh gRPC plaintext tới Messaging, Community kèm service token và AccessChecker dùng chúng
@Configuration
public class GrpcClientsConfig {

  // Kênh tới Messaging, đóng khi context tắt
  @Bean(destroyMethod = "shutdownNow")
  ManagedChannel messagingChannel(
      @Value("${app.grpc.messaging.target}") String target,
      @Value("${app.grpc.service-token:}") String token) {
    return channel(target, token);
  }

  // Kênh tới Community, đóng khi context tắt
  @Bean(destroyMethod = "shutdownNow")
  ManagedChannel communityChannel(
      @Value("${app.grpc.community.target}") String target,
      @Value("${app.grpc.service-token:}") String token) {
    return channel(target, token);
  }

  // Bean AccessChecker duy nhất, gọi gRPC không qua circuit breaker
  @Bean
  AccessChecker accessChecker(
      @Qualifier("messagingChannel") ManagedChannel messaging,
      @Qualifier("communityChannel") ManagedChannel community) {
    return new GrpcAccessChecker(messaging, community);
  }

  // Kênh plaintext (mạng Docker nội bộ, TLS ở BL-13) round_robin, gắn token mỗi call, kết nối ngay
  static ManagedChannel channel(String target, String token) {
    ManagedChannel channel =
        ManagedChannelBuilder.forTarget(target)
            .usePlaintext()
            .defaultLoadBalancingPolicy("round_robin")
            .intercept(new ClientMetadataInterceptor("realtime-gateway", token))
            .build();
    channel.getState(true);
    return channel;
  }
}
