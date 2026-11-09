package com.nexus.common.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

// Xác thực JWT user (authorization) hoặc service-token (x-service-token), đặt Context và MDC
public class AuthServerInterceptor implements ServerInterceptor {

  private static final String HEALTH_SERVICE = "grpc.health.v1.Health";
  private static final String BEARER = "Bearer ";

  private final JwtDecoder decoder;
  private final Map<String, ServiceCaller> callers;

  // Chỉ nhận JWT user, không có service caller nào
  public AuthServerInterceptor(JwtDecoder decoder) {
    this(decoder, Map.of());
  }

  // Nhận decoder dùng chung với phần REST và các service caller (tên -> token và allowlist)
  public AuthServerInterceptor(JwtDecoder decoder, Map<String, ServiceCaller> callers) {
    this.decoder = decoder;
    this.callers = callers;
  }

  // Có authorization thì xác thực JWT, không thì xác thực service-token, bỏ qua Health
  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (HEALTH_SERVICE.equals(call.getMethodDescriptor().getServiceName())) {
      return next.startCall(call, headers);
    }
    String authorization = headers.get(GrpcAuth.AUTHORIZATION);
    Context context = Context.current();
    Map<String, String> mdc = new HashMap<>();
    if (authorization != null) {
      UUID userId = authenticate(authorization);
      if (userId == null) {
        return reject(call, Status.UNAUTHENTICATED.withDescription("invalid or missing token"));
      }
      context = context.withValue(GrpcAuth.USER_ID, userId);
      mdc.put("userId", userId.toString());
    } else {
      String token = headers.get(GrpcAuth.SERVICE_TOKEN);
      String caller = token == null ? null : callerOf(token);
      if (caller == null) {
        return reject(call, Status.UNAUTHENTICATED.withDescription("invalid or missing token"));
      }
      if (!callers.get(caller).methods().contains(call.getMethodDescriptor().getFullMethodName())) {
        return reject(call, Status.PERMISSION_DENIED.withDescription("method not allowed"));
      }
      context = context.withValue(GrpcAuth.CALLER, caller);
      mdc.put("callerService", caller);
    }
    mdc.put("requestId", requestIdOf(headers));
    ServerCall.Listener<ReqT> delegate = Contexts.interceptCall(context, call, headers, next);
    return new MdcListener<>(delegate, mdc);
  }

  // Đóng call bằng status lỗi và trả listener rỗng
  private static <ReqT, RespT> ServerCall.Listener<ReqT> reject(
      ServerCall<ReqT, RespT> call, Status status) {
    call.close(status, new Metadata());
    return new ServerCall.Listener<>() {};
  }

  // Lấy x-request-id, thiếu thì sinh mới
  private static String requestIdOf(Metadata headers) {
    String requestId = headers.get(GrpcAuth.REQUEST_ID);
    return requestId == null ? UUID.randomUUID().toString() : requestId;
  }

  // Tên caller có token khớp (MessageDigest.isEqual, duyệt hết), null nếu không có
  private String callerOf(String token) {
    byte[] given = token.getBytes(StandardCharsets.UTF_8);
    String found = null;
    for (Map.Entry<String, ServiceCaller> entry : callers.entrySet()) {
      String configured = entry.getValue().token();
      if (configured != null
          && !configured.isBlank()
          && MessageDigest.isEqual(given, configured.getBytes(StandardCharsets.UTF_8))) {
        found = entry.getKey();
      }
    }
    return found;
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

  // Listener đặt MDC quanh mỗi callback rồi xóa ngay để không rò sang call khác
  private static final class MdcListener<ReqT>
      extends io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

    private final Map<String, String> mdc;

    // Giữ giá trị MDC của call
    MdcListener(ServerCall.Listener<ReqT> delegate, Map<String, String> mdc) {
      super(delegate);
      this.mdc = mdc;
    }

    // Chạy một callback với MDC được đặt, xóa MDC khi xong
    private void withMdc(Runnable callback) {
      mdc.forEach(MDC::put);
      try {
        callback.run();
      } finally {
        mdc.keySet().forEach(MDC::remove);
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
