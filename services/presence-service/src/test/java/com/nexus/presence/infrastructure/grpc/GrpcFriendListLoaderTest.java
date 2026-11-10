package com.nexus.presence.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.grpc.ClientMetadataInterceptor;
import com.nexus.presence.application.relation.RelationUnavailableException;
import com.nexus.proto.messaging.v1.ListFriendIdsRequest;
import com.nexus.proto.messaging.v1.ListFriendIdsResponse;
import com.nexus.proto.messaging.v1.SocialServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra GrpcFriendListLoader với server gRPC Netty thật: danh sách id và lỗi
class GrpcFriendListLoaderTest {

  // Kịch bản: Messaging trả một bạn
  // Input: friendIdsOf(user) qua kênh tới SocialService giả
  // Output: đúng một id
  @Test
  void returnsFriendIds() throws Exception {
    UUID f = UUID.randomUUID();
    Server server =
        NettyServerBuilder.forPort(0)
            .addService(
                // Giả SocialService trả f cho mọi user
                new SocialServiceGrpc.SocialServiceImplBase() {
                  // Trả danh sách bạn cố định
                  @Override
                  public void listFriendIds(
                      ListFriendIdsRequest request,
                      StreamObserver<ListFriendIdsResponse> observer) {
                    observer.onNext(
                        ListFriendIdsResponse.newBuilder().addFriendIds(f.toString()).build());
                    observer.onCompleted();
                  }
                })
            .build()
            .start();
    ManagedChannel channel = channelTo(server.getPort());
    try {
      assertThat(new GrpcFriendListLoader(channel).friendIdsOf(UUID.randomUUID()))
          .isEqualTo(Set.of(f));
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  // Kịch bản: Messaging không chạy
  // Input: friendIdsOf tới cổng không có ai lắng nghe
  // Output: ném RelationUnavailableException
  @Test
  void failureBecomesRelationUnavailable() {
    ManagedChannel channel = channelTo(1);
    try {
      assertThatThrownBy(() -> new GrpcFriendListLoader(channel).friendIdsOf(UUID.randomUUID()))
          .isInstanceOf(RelationUnavailableException.class);
    } finally {
      channel.shutdownNow();
    }
  }

  // Kênh plaintext tới localhost kèm interceptor service token "tok"
  private static ManagedChannel channelTo(int port) {
    return ManagedChannelBuilder.forAddress("localhost", port)
        .usePlaintext()
        .intercept(new ClientMetadataInterceptor("presence-service", "tok"))
        .build();
  }
}
