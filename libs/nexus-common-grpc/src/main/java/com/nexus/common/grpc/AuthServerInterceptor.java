package com.nexus.common.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

// Xác thực JWT user trong metadata authorization, đặt userId vào Context và MDC cho handler
public class AuthServerInterceptor implements ServerInterceptor {

  private static final String HEALTH_SERVICE = "grpc.health.v1.Health";
  private static final String BEARER = "Bearer ";

  private final JwtDecoder decoder;

  // Nhận decoder dùng chung với phần REST của service
  public AuthServerInterceptor(JwtDecoder decoder) {
    this.decoder = decoder;
  }

  // Từ chối call thiếu hoặc sai token, bỏ qua Health, còn lại chạy handler với Context và MDC
  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (HEALTH_SERVICE.equals(call.getMethodDescriptor().getServiceName())) {
      return next.startCall(call, headers);
    }
    UUID userId = authenticate(headers.get(GrpcAuth.AUTHORIZATION));
    if (userId == null) {
      call.close(
          Status.UNAUTHENTICATED.withDescription("invalid or missing token"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    String requestId = headers.get(GrpcAuth.REQUEST_ID);
    if (requestId == null) {
      requestId = UUID.randomUUID().toString();
    }
    Context context = Context.current().withValue(GrpcAuth.USER_ID, userId);
    ServerCall.Listener<ReqT> delegate = Contexts.interceptCall(context, call, headers, next);
    return new MdcListener<>(delegate, userId.toString(), requestId);
  }

  // Giải mã Bearer token thành userId (sub), trả null nếu header hoặc token không hợp lệ
  private UUID authenticate(String authorization) {
    if (authorization == null || !authorization.startsWith(BEARER)) {
      return null;
    }
    try {
      Jwt jwt = decoder.decode(authorization.substring(BEARER.length()));
      return UUID.fromString(jwt.getSubject());
    } catch (JwtException | IllegalArgumentException | NullPointerException e) {
      return null;
    }
  }

  // Listener đặt MDC userId và requestId quanh mỗi callback rồi xóa ngay để không rò sang call khác
  private static final class MdcListener<ReqT>
      extends io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

    private final String userId;
    private final String requestId;

    // Giữ giá trị MDC của call
    MdcListener(ServerCall.Listener<ReqT> delegate, String userId, String requestId) {
      super(delegate);
      this.userId = userId;
      this.requestId = requestId;
    }

    // Chạy một callback với MDC được đặt, xóa MDC khi xong
    private void withMdc(Runnable callback) {
      MDC.put("userId", userId);
      MDC.put("requestId", requestId);
      try {
        callback.run();
      } finally {
        MDC.remove("userId");
        MDC.remove("requestId");
      }
    }

    // Chạy xử lý message với MDC
    @Override
    public void onMessage(ReqT message) {
      withMdc(() -> super.onMessage(message));
    }

    // Chạy xử lý half-close (nơi handler unary chạy) với MDC
    @Override
    public void onHalfClose() {
      withMdc(super::onHalfClose);
    }

    // Chạy xử lý hủy call với MDC rồi xóa MDC
    @Override
    public void onCancel() {
      withMdc(super::onCancel);
    }

    // Chạy xử lý hoàn tất call với MDC rồi xóa MDC
    @Override
    public void onComplete() {
      withMdc(super::onComplete);
    }

    // Chạy xử lý ready với MDC
    @Override
    public void onReady() {
      withMdc(super::onReady);
    }
  }
}
