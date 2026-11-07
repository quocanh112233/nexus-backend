package com.nexus.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.proto.community.v1.CheckServerMembershipRequest;
import com.nexus.proto.community.v1.CheckServerMembershipResponse;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

// Kiểm thử ClientMetadataInterceptor: server giả ghi lại metadata nhận được
class ClientMetadataInterceptorTest {

  private final AtomicReference<Metadata> seen = new AtomicReference<>();

  // Dọn SecurityContext và MDC sau mỗi test
  @AfterEach
  void clean() {
    SecurityContextHolder.clearContext();
    MDC.clear();
  }

  // Gọi một RPC qua ClientMetadataInterceptor và trả metadata mà server thấy
  private Metadata call() {
    ServerInterceptor capture =
        new ServerInterceptor() {
          // Server giả chụp lại metadata nhận được
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            seen.set(headers);
            return next.startCall(call, headers);
          }
        };
    PermissionServiceGrpc.PermissionServiceImplBase service =
        new PermissionServiceGrpc.PermissionServiceImplBase() {
          // Handler giả trả kết quả rỗng
          @Override
          public void checkServerMembership(
              CheckServerMembershipRequest request,
              StreamObserver<CheckServerMembershipResponse> observer) {
            observer.onNext(CheckServerMembershipResponse.getDefaultInstance());
            observer.onCompleted();
          }
        };
    try (GrpcTestServer server =
        new GrpcTestServer(capture, new ClientMetadataInterceptor("messaging-service"), service)) {
      PermissionServiceGrpc.newBlockingStub(server.channel())
          .checkServerMembership(CheckServerMembershipRequest.getDefaultInstance());
    }
    return seen.get();
  }

  // Kịch bản: có SecurityContext JWT và MDC requestId
  // Input: JwtAuthenticationToken(token-abc), MDC requestId=req-9, caller messaging-service
  // Output: server thấy Bearer token-abc, x-request-id req-9, x-caller-service messaging-service
  @Test
  void copiesAuthorizationRequestIdAndCaller() {
    Jwt token =
        new Jwt(
            "token-abc",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "RS256"),
            Map.of("sub", "u"));
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    MDC.put("requestId", "req-9");
    Metadata headers = call();
    assertThat(headers.get(GrpcAuth.AUTHORIZATION)).isEqualTo("Bearer token-abc");
    assertThat(headers.get(GrpcAuth.REQUEST_ID)).isEqualTo("req-9");
    assertThat(headers.get(GrpcAuth.CALLER_SERVICE)).isEqualTo("messaging-service");
  }

  // Kịch bản: không có SecurityContext và MDC
  // Input: ngữ cảnh trống
  // Output: server chỉ thấy x-caller-service, không có authorization và x-request-id
  @Test
  void skipsMissingHeaders() {
    Metadata headers = call();
    assertThat(headers.containsKey(GrpcAuth.AUTHORIZATION)).isFalse();
    assertThat(headers.containsKey(GrpcAuth.REQUEST_ID)).isFalse();
    assertThat(headers.get(GrpcAuth.CALLER_SERVICE)).isEqualTo("messaging-service");
  }

  // Kịch bản: xác thực không phải JWT thì không chép authorization
  // Input: TestingAuthenticationToken
  // Output: server không thấy authorization
  @Test
  void ignoresNonJwtAuthentication() {
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p"));
    assertThat(call().containsKey(GrpcAuth.AUTHORIZATION)).isFalse();
  }
}
