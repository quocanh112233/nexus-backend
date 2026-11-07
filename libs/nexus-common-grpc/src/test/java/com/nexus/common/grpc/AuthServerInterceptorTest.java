package com.nexus.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.proto.community.v1.CheckServerMembershipRequest;
import com.nexus.proto.community.v1.CheckServerMembershipResponse;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import com.nexus.testsupport.TestJwt;
import com.nimbusds.jose.JWSAlgorithm;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// Kiểm thử AuthServerInterceptor trên server gRPC in-process
class AuthServerInterceptorTest {

  private static TestJwt jwt;
  private static TestJwt.JwksServer jwks;
  private static JwtDecoder decoder;

  private final AtomicReference<UUID> seenUser = new AtomicReference<>();
  private final AtomicReference<String> seenMdcUser = new AtomicReference<>();
  private final AtomicReference<String> seenMdcRequest = new AtomicReference<>();
  private GrpcTestServer server;

  // Dựng JWKS giả và decoder chỉ nhận RS256
  @BeforeAll
  static void startJwks() {
    jwt = TestJwt.create();
    jwks = jwt.serveJwks();
    decoder =
        NimbusJwtDecoder.withJwkSetUri(jwks.uri()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
  }

  // Dừng JWKS giả
  @AfterAll
  static void stopJwks() {
    jwks.close();
  }

  // Dựng server có interceptor, service ghi lại user và MDC, và service Health
  @BeforeEach
  void startServer() {
    PermissionServiceGrpc.PermissionServiceImplBase service =
        new PermissionServiceGrpc.PermissionServiceImplBase() {
          // Handler giả ghi lại userId và MDC của call
          @Override
          public void checkServerMembership(
              CheckServerMembershipRequest request,
              StreamObserver<CheckServerMembershipResponse> observer) {
            seenUser.set(GrpcAuth.userId().orElse(null));
            seenMdcUser.set(MDC.get("userId"));
            seenMdcRequest.set(MDC.get("requestId"));
            observer.onNext(CheckServerMembershipResponse.newBuilder().setIsMember(true).build());
            observer.onCompleted();
          }
        };
    server =
        new GrpcTestServer(
            new AuthServerInterceptor(decoder),
            null,
            service,
            new HealthStatusManager().getHealthService());
  }

  // Đóng server sau mỗi test
  @AfterEach
  void stopServer() {
    server.close();
  }

  // Gọi CheckServerMembership với header authorization và x-request-id tùy chọn
  private CheckServerMembershipResponse call(String authorization, String requestId) {
    Metadata headers = new Metadata();
    if (authorization != null) {
      headers.put(GrpcAuth.AUTHORIZATION, authorization);
    }
    if (requestId != null) {
      headers.put(GrpcAuth.REQUEST_ID, requestId);
    }
    return PermissionServiceGrpc.newBlockingStub(server.channel())
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
        .checkServerMembership(CheckServerMembershipRequest.getDefaultInstance());
  }

  // Khẳng định lời gọi bị từ chối UNAUTHENTICATED
  private void assertUnauthenticated(String authorization) {
    assertThatThrownBy(() -> call(authorization, null))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
  }

  // Kịch bản: không gửi header authorization
  // Input: lời gọi không metadata
  // Output: UNAUTHENTICATED
  @Test
  void missingTokenIsUnauthenticated() {
    assertUnauthenticated(null);
  }

  // Kịch bản: token đã hết hạn quá thời gian lệch đồng hồ cho phép
  // Input: Bearer token có exp cách đây 1 giờ
  // Output: UNAUTHENTICATED
  @Test
  void expiredTokenIsUnauthenticated() {
    String token = jwt.token(UUID.randomUUID(), "alice", Instant.now().minusSeconds(3600));
    assertUnauthenticated("Bearer " + token);
  }

  // Kịch bản: token không phải JWT
  // Input: Bearer garbage
  // Output: UNAUTHENTICATED
  @Test
  void garbageTokenIsUnauthenticated() {
    assertUnauthenticated("Bearer garbage");
  }

  // Kịch bản: token ký bằng thuật toán RS512 không được chấp nhận
  // Input: Bearer token RS512 cùng khóa
  // Output: UNAUTHENTICATED
  @Test
  void wrongAlgorithmIsUnauthenticated() {
    String token = jwt.tokenWithAlgorithm(UUID.randomUUID(), "alice", JWSAlgorithm.RS512);
    assertUnauthenticated("Bearer " + token);
  }

  // Kịch bản: token hợp lệ thì handler thấy userId trong Context và MDC cùng requestId gửi lên
  // Input: Bearer token hợp lệ của user, x-request-id = req-1
  // Output: userId và MDC userId bằng sub, MDC requestId bằng req-1, MDC sạch sau call
  @Test
  void validTokenExposesUserIdAndMdc() {
    UUID userId = UUID.randomUUID();
    CheckServerMembershipResponse response = call("Bearer " + jwt.token(userId, "alice"), "req-1");
    assertThat(response.getIsMember()).isTrue();
    assertThat(seenUser.get()).isEqualTo(userId);
    assertThat(seenMdcUser.get()).isEqualTo(userId.toString());
    assertThat(seenMdcRequest.get()).isEqualTo("req-1");
    assertThat(MDC.get("userId")).isNull();
  }

  // Kịch bản: thiếu x-request-id thì interceptor tự sinh requestId mới
  // Input: Bearer token hợp lệ, không có x-request-id
  // Output: MDC requestId khác rỗng
  @Test
  void missingRequestIdIsGenerated() {
    call("Bearer " + jwt.token(UUID.randomUUID(), "alice"), null);
    assertThat(seenMdcRequest.get()).isNotBlank();
  }

  // Kịch bản: dịch vụ Health không cần token
  // Input: grpc.health.v1.Health/Check không metadata
  // Output: SERVING
  @Test
  void healthServiceNeedsNoToken() {
    HealthCheckResponse response =
        HealthGrpc.newBlockingStub(server.channel()).check(HealthCheckRequest.getDefaultInstance());
    assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
  }
}
