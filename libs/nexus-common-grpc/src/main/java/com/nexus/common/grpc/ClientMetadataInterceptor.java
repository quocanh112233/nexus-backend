package com.nexus.common.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

// Chép JWT user hoặc service token, requestId và tên service gọi vào mọi call gRPC đi ra
public class ClientMetadataInterceptor implements ClientInterceptor {

  private final String callerService;
  private final String serviceToken;

  // Nhận tên service gọi để gắn vào x-caller-service, không dùng service token
  public ClientMetadataInterceptor(String callerService) {
    this(callerService, null);
  }

  // Thêm service token gắn vào x-service-token khi call không có JWT của user
  public ClientMetadataInterceptor(String callerService, String serviceToken) {
    this.callerService = callerService;
    this.serviceToken = serviceToken;
  }

  // Gắn các header còn dữ liệu khi call bắt đầu, header thiếu thì bỏ qua
  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
    return new SimpleForwardingClientCall<>(next.newCall(method, options)) {
      // Gắn authorization hoặc x-service-token, x-request-id và x-caller-service rồi bắt đầu call
      @Override
      public void start(Listener<RespT> listener, Metadata headers) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
          headers.put(
              GrpcAuth.AUTHORIZATION, "Bearer " + jwtAuthentication.getToken().getTokenValue());
        } else if (serviceToken != null && !serviceToken.isBlank()) {
          headers.put(GrpcAuth.SERVICE_TOKEN, serviceToken);
        }
        String requestId = MDC.get("requestId");
        if (requestId != null) {
          headers.put(GrpcAuth.REQUEST_ID, requestId);
        }
        headers.put(GrpcAuth.CALLER_SERVICE, callerService);
        super.start(listener, headers);
      }
    };
  }
}
