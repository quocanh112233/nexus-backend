package com.nexus.presence.infrastructure.grpc;

import com.nexus.common.grpc.ClientMetadataInterceptor;
import com.nexus.presence.application.relation.FriendListLoader;
import com.nexus.presence.application.relation.ServerListLoader;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Dựng kênh gRPC plaintext tới Community, Messaging kèm service token và hai loader dùng chúng
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

  // Bean ServerListLoader duy nhất, gọi gRPC Community
  @Bean
  ServerListLoader serverListLoader(ManagedChannel communityChannel) {
    return new GrpcServerListLoader(communityChannel);
  }

  // Bean FriendListLoader duy nhất, gọi gRPC Messaging
  @Bean
  FriendListLoader friendListLoader(ManagedChannel messagingChannel) {
    return new GrpcFriendListLoader(messagingChannel);
  }

  // Kênh plaintext round_robin kèm service token, kết nối ngay thay vì chờ call đầu
  private static ManagedChannel channel(String target, String token) {
    ManagedChannel channel =
        ManagedChannelBuilder.forTarget(target)
            .usePlaintext()
            .defaultLoadBalancingPolicy("round_robin")
            .intercept(new ClientMetadataInterceptor("presence-service", token))
            .build();
    channel.getState(true);
    return channel;
  }
}
