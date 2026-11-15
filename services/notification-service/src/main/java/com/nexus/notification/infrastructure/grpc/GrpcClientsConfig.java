package com.nexus.notification.infrastructure.grpc;

import com.nexus.common.grpc.ClientMetadataInterceptor;
import com.nexus.notification.application.port.MembershipSnapshotSource;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Dựng kênh gRPC plaintext tới Community, Messaging kèm service token và nguồn snapshot dùng chúng
@Configuration
public class GrpcClientsConfig {

  // Kênh tới Community round_robin (mạng Docker nội bộ, TLS ở BL-13), kết nối ngay, đóng khi tắt
  @Bean(destroyMethod = "shutdownNow")
  ManagedChannel communityChannel(
      @Value("${app.grpc.community.target}") String target,
      @Value("${app.grpc.service-token:}") String token) {
    return channel(target, token);
  }

  // Kênh tới Messaging, cùng cấu hình và service token với kênh Community
  @Bean(destroyMethod = "shutdownNow")
  ManagedChannel messagingChannel(
      @Value("${app.grpc.messaging.target}") String target,
      @Value("${app.grpc.service-token:}") String token) {
    return channel(target, token);
  }

  // Bean MembershipSnapshotSource duy nhất, stream từ Community và Messaging
  @Bean
  MembershipSnapshotSource membershipSnapshotSource(
      ManagedChannel communityChannel,
      ManagedChannel messagingChannel,
      @Value("${nexus.notification.backfill.rpc-deadline:60s}") Duration deadline) {
    return new GrpcMembershipSnapshotSource(communityChannel, messagingChannel, deadline);
  }

  // Kênh plaintext round_robin kèm service token, kết nối ngay thay vì chờ call đầu
  private static ManagedChannel channel(String target, String token) {
    ManagedChannel channel =
        ManagedChannelBuilder.forTarget(target)
            .usePlaintext()
            .defaultLoadBalancingPolicy("round_robin")
            .intercept(new ClientMetadataInterceptor("notification-service", token))
            .build();
    channel.getState(true);
    return channel;
  }
}
