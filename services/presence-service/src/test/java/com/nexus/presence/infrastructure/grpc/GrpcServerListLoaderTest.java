package com.nexus.presence.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.grpc.ClientMetadataInterceptor;
import com.nexus.common.grpc.GrpcAuth;
import com.nexus.presence.application.relation.RelationUnavailableException;
import com.nexus.proto.community.v1.ListServerIdsForUserRequest;
import com.nexus.proto.community.v1.ListServerIdsForUserResponse;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

// Kiểm tra GrpcServerListLoader với server gRPC Netty thật: danh sách id, token và lỗi
class GrpcServerListLoaderTest {

  // Kịch bản: Community trả hai server
  // Input: serverIdsOf(user) qua kênh có service token "tok"
  // Output: đúng hai id và server nhận x-service-token = "tok"
  @Test
  void returnsServerIdsAndSendsServiceToken() throws Exception {
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    AtomicReference<String> token = new AtomicReference<>();
    ServerInterceptor capture =
        new ServerInterceptor() {
          // Ghi x-service-token của call rồi chuyển tiếp
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            token.set(headers.get(GrpcAuth.SERVICE_TOKEN));
            return next.startCall(call, headers);
          }
        };
    Server server =
        NettyServerBuilder.forPort(0)
            .addService(
                ServerInterceptors.intercept(
                    // Giả PermissionService trả s1, s2 cho mọi user
                    new PermissionServiceGrpc.PermissionServiceImplBase() {
                      // Trả danh sách server cố định
                      @Override
                      public void listServerIdsForUser(
                          ListServerIdsForUserRequest request,
                          StreamObserver<ListServerIdsForUserResponse> observer) {
                        observer.onNext(
                            ListServerIdsForUserResponse.newBuilder()
                                .addServerIds(s1.toString())
                                .addServerIds(s2.toString())
                                .build());
                        observer.onCompleted();
                      }
                    },
                    capture))
            .build()
            .start();
    ManagedChannel channel = channelTo(server.getPort());
    try {
      assertThat(new GrpcServerListLoader(channel).serverIdsOf(UUID.randomUUID()))
          .isEqualTo(Set.of(s1, s2));
      assertThat(token.get()).isEqualTo("tok");
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  // Kịch bản: Community không chạy
  // Input: serverIdsOf tới cổng không có ai lắng nghe
  // Output: ném RelationUnavailableException
  @Test
  void failureBecomesRelationUnavailable() {
    ManagedChannel channel = channelTo(1);
    try {
      assertThatThrownBy(() -> new GrpcServerListLoader(channel).serverIdsOf(UUID.randomUUID()))
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
