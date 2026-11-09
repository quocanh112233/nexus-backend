package com.nexus.common.grpc;

import io.grpc.Context;
import io.grpc.Metadata;
import java.util.Optional;
import java.util.UUID;

// Hằng số metadata gRPC dùng chung và truy cập userId hoặc service gọi của request hiện tại
public final class GrpcAuth {

  public static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<String> REQUEST_ID =
      Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<String> CALLER_SERVICE =
      Metadata.Key.of("x-caller-service", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<String> SERVICE_TOKEN =
      Metadata.Key.of("x-service-token", Metadata.ASCII_STRING_MARSHALLER);

  static final Context.Key<UUID> USER_ID = Context.key("nexus-user-id");
  static final Context.Key<String> CALLER = Context.key("nexus-caller-service");

  // Lớp tiện ích, không tạo instance
  private GrpcAuth() {}

  // Id user đã xác thực của call hiện tại, rỗng nếu chưa qua AuthServerInterceptor
  public static Optional<UUID> userId() {
    return Optional.ofNullable(USER_ID.get());
  }

  // Tên service gọi đã xác thực bằng x-service-token, rỗng nếu call do user gọi
  public static Optional<String> callerService() {
    return Optional.ofNullable(CALLER.get());
  }
}
