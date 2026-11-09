package com.nexus.common.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.security.oauth2.jwt.JwtDecoder;

// Đăng ký interceptor JWT, service-token và ánh xạ lỗi khi có JwtDecoder, chặn JWT của Boot
@AutoConfiguration(
    afterName =
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
    beforeName =
        "org.springframework.boot.grpc.server.autoconfigure.security.GrpcServerOAuth2ResourceServerAutoConfiguration")
@EnableConfigurationProperties(GrpcProperties.class)
public class NexusCommonGrpcAutoConfiguration {

  static final int AUTH_ORDER = 10;
  static final int EXCEPTION_MAPPING_ORDER = 20;

  // Interceptor xác thực JWT cho mọi RPC, chạy ngoài cùng
  @Bean
  @GlobalServerInterceptor
  @Order(AUTH_ORDER)
  @ConditionalOnBean(JwtDecoder.class)
  AuthServerInterceptor authServerInterceptor(JwtDecoder decoder, GrpcProperties properties) {
    return new AuthServerInterceptor(decoder, properties.serviceCallers());
  }

  // Interceptor ánh xạ exception sang status gRPC, chạy trong interceptor xác thực
  @Bean
  @GlobalServerInterceptor
  @Order(EXCEPTION_MAPPING_ORDER)
  @ConditionalOnBean(JwtDecoder.class)
  ExceptionMappingServerInterceptor exceptionMappingServerInterceptor() {
    return new ExceptionMappingServerInterceptor();
  }

  // Bean giữ chỗ khiến Boot không tự dựng interceptor JWT thứ hai, không đăng ký làm global
  @Bean
  @ConditionalOnMissingBean(AuthenticationProcessInterceptor.class)
  AuthenticationProcessInterceptor bootJwtInterceptorSuppressor() {
    return new PassThroughInterceptor();
  }

  // Interceptor cho mọi call đi qua, chỉ để chiếm điều kiện ConditionalOnMissingBean của Boot
  private static final class PassThroughInterceptor extends AuthenticationProcessInterceptor {

    // Khởi tạo không cần authentication manager, extractor hay authorization manager
    PassThroughInterceptor() {
      super(null, null, null);
    }

    // Chuyển thẳng call cho handler kế tiếp
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      return next.startCall(call, headers);
    }
  }
}
