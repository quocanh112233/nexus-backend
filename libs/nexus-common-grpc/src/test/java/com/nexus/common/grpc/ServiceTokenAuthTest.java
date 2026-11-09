package com.nexus.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.proto.community.v1.CheckServerMembershipRequest;
import com.nexus.proto.community.v1.CheckServerMembershipResponse;
import com.nexus.proto.community.v1.ListServerIdsForUserRequest;
import com.nexus.proto.community.v1.ListServerIdsForUserResponse;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import com.nexus.testsupport.TestJwt;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// Kiểm thử xác thực x-service-token của AuthServerInterceptor trên server gRPC in-process
class ServiceTokenAuthTest {

  private static final String TOKEN = "dummy-realtime-token";
  private static final String MEMBERSHIP =
      "nexus.community.v1.PermissionService/CheckServerMembership";

  private static TestJwt jwt;
  private static TestJwt.JwksServer jwks;
  private static JwtDecoder decoder;

  private final AtomicReference<Optional<String>> seenCaller = new AtomicReference<>();
  private final AtomicReference<Optional<UUID>> seenUser = new AtomicReference<>();
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

  // Dựng server chỉ cho caller realtime-gateway gọi CheckServerMembership bằng TOKEN
  @BeforeEach
  void startServer() {
    PermissionServiceGrpc.PermissionServiceImplBase service =
        new PermissionServiceGrpc.PermissionServiceImplBase() {
          // Handler giả ghi lại caller và user của call
          @Override
          public void checkServerMembership(
              CheckServerMembershipRequest request,
              StreamObserver<CheckServerMembershipResponse> observer) {
            seenCaller.set(GrpcAuth.callerService());
            seenUser.set(GrpcAuth.userId());
            observer.onNext(CheckServerMembershipResponse.newBuilder().setIsMember(true).build());
            observer.onCompleted();
          }

          // Handler giả cho method không nằm trong allowlist
          @Override
          public void listServerIdsForUser(
              ListServerIdsForUserRequest request,
              StreamObserver<ListServerIdsForUserResponse> observer) {
            observer.onNext(ListServerIdsForUserResponse.getDefaultInstance());
            observer.onCompleted();
          }
        };
    Map<String, ServiceCaller> callers =
        Map.of("realtime-gateway", new ServiceCaller(TOKEN, List.of(MEMBERSHIP)));
    server = new GrpcTestServer(new AuthServerInterceptor(decoder, callers), null, service);
  }

  // Đóng server sau mỗi test
  @AfterEach
  void stopServer() {
    server.close();
  }

  // Gọi CheckServerMembership với các header tùy chọn
  private CheckServerMembershipResponse membership(String authorization, String serviceToken) {
    return PermissionServiceGrpc.newBlockingStub(server.channel())
        .withInterceptors(
            MetadataUtils.newAttachHeadersInterceptor(headers(authorization, serviceToken)))
        .checkServerMembership(CheckServerMembershipRequest.getDefaultInstance());
  }

  // Dựng metadata với authorization và x-service-token nếu khác null
  private static Metadata headers(String authorization, String serviceToken) {
    Metadata headers = new Metadata();
    if (authorization != null) {
      headers.put(GrpcAuth.AUTHORIZATION, authorization);
    }
    if (serviceToken != null) {
      headers.put(GrpcAuth.SERVICE_TOKEN, serviceToken);
    }
    return headers;
  }

  // Khẳng định lời gọi bị từ chối với mã status cho trước
  private static void assertCode(Runnable call, Status.Code code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode()).isEqualTo(code));
  }

  // Kịch bản: token hợp lệ và method nằm trong allowlist của caller
  // Input: x-service-token đúng của realtime-gateway gọi CheckServerMembership
  // Output: handler chạy với callerService realtime-gateway và không có userId
  @Test
  void validTokenInAllowlistRunsHandlerWithoutUser() {
    assertThat(membership(null, TOKEN).getIsMember()).isTrue();
    assertThat(seenCaller.get()).contains("realtime-gateway");
    assertThat(seenUser.get()).isEmpty();
  }

  // Kịch bản: token hợp lệ nhưng method ngoài allowlist của caller
  // Input: x-service-token đúng gọi ListServerIdsForUser
  // Output: PERMISSION_DENIED
  @Test
  void validTokenOutsideAllowlistIsPermissionDenied() {
    assertCode(
        () ->
            PermissionServiceGrpc.newBlockingStub(server.channel())
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers(null, TOKEN)))
                .listServerIdsForUser(ListServerIdsForUserRequest.getDefaultInstance()),
        Status.Code.PERMISSION_DENIED);
  }

  // Kịch bản: token không khớp caller nào
  // Input: x-service-token sai
  // Output: UNAUTHENTICATED
  @Test
  void wrongTokenIsUnauthenticated() {
    assertCode(() -> membership(null, "wrong"), Status.Code.UNAUTHENTICATED);
  }

  // Kịch bản: thiếu cả authorization và x-service-token
  // Input: lời gọi không metadata
  // Output: UNAUTHENTICATED
  @Test
  void noCredentialsIsUnauthenticated() {
    assertCode(() -> membership(null, null), Status.Code.UNAUTHENTICATED);
  }

  // Kịch bản: JWT user hợp lệ vẫn chạy theo đường cũ
  // Input: Bearer token hợp lệ, không có x-service-token
  // Output: handler thấy userId và không có callerService
  @Test
  void userJwtStillWorks() {
    UUID user = UUID.randomUUID();
    membership("Bearer " + jwt.token(user, "alice"), null);
    assertThat(seenUser.get()).contains(user);
    assertThat(seenCaller.get()).isEmpty();
  }

  // Kịch bản: có authorization thì đi đường JWT, bỏ qua service token hợp lệ đi kèm
  // Input: authorization rác kèm x-service-token đúng
  // Output: UNAUTHENTICATED
  @Test
  void authorizationTakesPrecedenceOverServiceToken() {
    assertCode(() -> membership("Bearer garbage", TOKEN), Status.Code.UNAUTHENTICATED);
  }

  // Kịch bản: so sánh token phải bằng MessageDigest.isEqual
  // Input: bytecode của AuthServerInterceptor
  // Output: tham chiếu java/security/MessageDigest và isEqual
  @Test
  void tokenComparisonIsConstantTime() throws IOException {
    try (InputStream in =
        AuthServerInterceptor.class.getResourceAsStream("AuthServerInterceptor.class")) {
      String bytecode = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
      assertThat(bytecode).contains("java/security/MessageDigest").contains("isEqual");
    }
  }

  // Kịch bản: hai caller dùng chung một token thì allowlist không xác định được
  // Input: GrpcProperties với hai caller cùng token, và hai caller cùng token trống
  // Output: trùng token ném IllegalArgumentException, token trống không bị tính trùng
  @Test
  void duplicateTokensAreRejected() {
    ServiceCaller a = new ServiceCaller("same", List.of());
    ServiceCaller b = new ServiceCaller("same", List.of());
    assertThatThrownBy(() -> new GrpcProperties(Map.of("a", a, "b", b)))
        .isInstanceOf(IllegalArgumentException.class);
    ServiceCaller blank = new ServiceCaller("", List.of());
    assertThat(new GrpcProperties(Map.of("a", blank, "b", blank)).serviceCallers()).hasSize(2);
  }
}
