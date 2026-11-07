package com.nexus.messaging.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.proto.community.v1.CheckChannelPermissionsRequest;
import com.nexus.proto.community.v1.CheckChannelPermissionsResponse;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

// Kiểm tra kênh dựng bằng đường production nói chuyện được với server Netty plaintext thật
class CommunityChannelPlaintextTest {

  // Kịch bản: client dựng bằng newChannel production nói chuyện với server Netty plaintext
  // Input: target dns:///localhost:<port>, server trả server_id cụ thể
  // Output: check thành công và trả đúng serverId
  @Test
  void channelFromProductionConfigTalksToPlaintextNettyServer() throws Exception {
    UUID serverId = UUID.randomUUID();
    Server server =
        NettyServerBuilder.forPort(0)
            .addService(
                new PermissionServiceGrpc.PermissionServiceImplBase() {
                  @Override
                  public void checkChannelPermissions(
                      CheckChannelPermissionsRequest request,
                      StreamObserver<CheckChannelPermissionsResponse> observer) {
                    observer.onNext(
                        CheckChannelPermissionsResponse.newBuilder()
                            .setServerId(serverId.toString())
                            .build());
                    observer.onCompleted();
                  }
                })
            .build()
            .start();
    ManagedChannel channel =
        CommunityChannelConfig.newChannel(
            "dns:///localhost:" + server.getPort(), ObservationRegistry.NOOP);
    try {
      CircuitBreaker breaker =
          CircuitBreaker.of(
              "community",
              CommunityChannelConfig.breakerConfig(20, 50f, Duration.ofSeconds(10), 3));
      GrpcChannelPermissionChecker checker =
          new GrpcChannelPermissionChecker(channel, breaker, Duration.ofSeconds(2));

      ChannelAccess access =
          checker.check(new UserId(UUID.randomUUID()), new ChannelId(UUID.randomUUID()));

      assertThat(access.serverId()).isEqualTo(serverId);
    } finally {
      channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }
  }
}
