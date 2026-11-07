package com.nexus.common.grpc;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.util.Optional;

// Dựng và đọc lỗi gRPC kèm google.rpc.ErrorInfo{reason, domain}
public final class GrpcStatuses {

  // Lớp tiện ích, không tạo instance
  private GrpcStatuses() {}

  // Tạo StatusRuntimeException mang ErrorInfo với reason và domain cho trước
  public static StatusRuntimeException error(
      Status.Code code, String domain, String reason, String message) {
    ErrorInfo info = ErrorInfo.newBuilder().setReason(reason).setDomain(domain).build();
    com.google.rpc.Status status =
        com.google.rpc.Status.newBuilder()
            .setCode(code.value())
            .setMessage(message)
            .addDetails(Any.pack(info))
            .build();
    return StatusProto.toStatusRuntimeException(status);
  }

  // Đọc reason từ ErrorInfo của lỗi, rỗng nếu lỗi không đính ErrorInfo
  public static Optional<String> reasonOf(StatusRuntimeException e) {
    com.google.rpc.Status status = StatusProto.fromThrowable(e);
    if (status == null) {
      return Optional.empty();
    }
    for (Any detail : status.getDetailsList()) {
      if (detail.is(ErrorInfo.class)) {
        try {
          return Optional.of(detail.unpack(ErrorInfo.class).getReason());
        } catch (InvalidProtocolBufferException ex) {
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }
}
