package com.nexus.messaging.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.grpc.AuthServerInterceptor;
import com.nexus.common.grpc.ClientMetadataInterceptor;
import com.nexus.common.grpc.GrpcAuth;
import com.nexus.common.grpc.ServiceCaller;
import com.nexus.messaging.interfaces.grpc.ChannelAccessGrpcService;
import com.nexus.proto.community.v1.CheckChannelPermissionsRequest;
import com.nexus.proto.community.v1.CheckChannelPermissionsResponse;
import com.nexus.proto.community.v1.Permission;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import com.nexus.proto.messaging.v1.ChannelAccessServiceGrpc;
import com.nexus.proto.messaging.v1.CheckChannelAccessRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

// Kiểm tra chuỗi gateway (service token) -> messaging -> Community (token của messaging)
class ServiceTokenChainTest {

  private static final String REALTIME_TOKEN = "dummy-realtime";
  private static final String MESSAGING_TOKEN = "dummy-messaging";

  private final AtomicReference<Optional<String>> communitySawCaller = new AtomicReference<>();
  private final AtomicReference<Metadata> communitySawHeaders = new AtomicReference<>();
  private Server community;
  private Server messaging;
  private ManagedChannel communityChannel;
  private ManagedChannel clientChannel;

  // Dựng Community giả có allowlist thật, messaging có ChannelAccessService nối tới Community
  @BeforeEach
  void setUp() throws Exception {
    SecurityContextHolder.clearContext();
    JwtDecoder noJwt =
        token -> {
          throw new IllegalStateException("no jwt expected");
        };
    String communityName = "community-" + UUID.randomUUID();
    community =
        InProcessServerBuilder.forName(communityName)
            .directExecutor()
            .intercept(new HeaderCapture())
            .intercept(
                new AuthServerInterceptor(
                    noJwt,
                    Map.of(
                        "messaging-service",
                        new ServiceCaller(
                            MESSAGING_TOKEN,
                            List.of(
                                "nexus.community.v1.PermissionService/CheckChannelPermissions")))))
            .addService(new FakeCommunity())
            .build()
            .start();
    communityChannel =
        InProcessChannelBuilder.forName(communityName)
            .directExecutor()
            .intercept(new ClientMetadataInterceptor("messaging-service", MESSAGING_TOKEN))
            .build();
    GrpcChannelPermissionChecker checker =
        new GrpcChannelPermissionChecker(
            communityChannel, CircuitBreaker.ofDefaults("community"), Duration.ofSeconds(2));
    String messagingName = "messaging-" + UUID.randomUUID();
    messaging =
        InProcessServerBuilder.forName(messagingName)
            .directExecutor()
            .intercept(
                new AuthServerInterceptor(
                    noJwt,
                    Map.of(
                        "realtime-gateway",
                        new ServiceCaller(
                            REALTIME_TOKEN,
                            List.of(
                                "nexus.messaging.v1.ChannelAccessService/CheckChannelAccess")))))
            .addService(new ChannelAccessGrpcService(checker))
            .build()
            .start();
    clientChannel = InProcessChannelBuilder.forName(messagingName).directExecutor().build();
  }

  // Tắt các kênh và server sau mỗi test
  @AfterEach
  void tearDown() throws Exception {
    clientChannel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    communityChannel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    messaging.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    community.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
  }

  // Stub gọi messaging với x-service-token cho trước
  private ChannelAccessServiceGrpc.ChannelAccessServiceBlockingStub stub(String serviceToken) {
    Metadata headers = new Metadata();
    headers.put(GrpcAuth.SERVICE_TOKEN, serviceToken);
    return ChannelAccessServiceGrpc.newBlockingStub(clientChannel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  // Kịch bản: gateway gọi messaging bằng service token, không có JWT user
  // Input: CheckChannelAccess với token realtime, SecurityContext trống
  // Output: can_view true, Community thấy caller messaging-service, token messaging, không JWT
  @Test
  void serviceTokenCallReachesCommunityWithMessagingToken() {
    var response =
        stub(REALTIME_TOKEN)
            .checkChannelAccess(
                CheckChannelAccessRequest.newBuilder()
                    .setUserId(UUID.randomUUID().toString())
                    .setChannelId(UUID.randomUUID().toString())
                    .build());

    assertThat(response.getCanView()).isTrue();
    assertThat(communitySawCaller.get()).contains("messaging-service");
    assertThat(communitySawHeaders.get().get(GrpcAuth.SERVICE_TOKEN)).isEqualTo(MESSAGING_TOKEN);
    assertThat(communitySawHeaders.get().containsKey(GrpcAuth.AUTHORIZATION)).isFalse();
  }

  // Kịch bản: token không hợp lệ chặn ngay ở messaging
  // Input: CheckChannelAccess với token lạ
  // Output: UNAUTHENTICATED và Community không bị gọi
  @Test
  void wrongTokenNeverReachesCommunity() {
    var stub = stub("wrong");
    CheckChannelAccessRequest request = CheckChannelAccessRequest.getDefaultInstance();

    assertThatThrownBy(() -> stub.checkChannelAccess(request))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(communitySawCaller.get()).isNull();
  }

  // Interceptor ghi lại metadata Community nhận được
  private final class HeaderCapture implements ServerInterceptor {
    // Lưu metadata rồi chuyển tiếp call
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      communitySawHeaders.set(headers);
      return next.startCall(call, headers);
    }
  }

  // Community giả cho mọi user quyền VIEW_CHANNEL và ghi lại caller cùng metadata (qua Context)
  private final class FakeCommunity extends PermissionServiceGrpc.PermissionServiceImplBase {
    // Ghi lại caller rồi trả quyền xem kênh
    @Override
    public void checkChannelPermissions(
        CheckChannelPermissionsRequest request,
        StreamObserver<CheckChannelPermissionsResponse> observer) {
      communitySawCaller.set(GrpcAuth.callerService());
      observer.onNext(
          CheckChannelPermissionsResponse.newBuilder()
              .setServerId(UUID.randomUUID().toString())
              .addGranted(Permission.PERMISSION_VIEW_CHANNEL)
              .build());
      observer.onCompleted();
    }
  }
}
