package com.nexus.common.grpc;

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Chuyển exception của handler thành status gRPC, không lộ chi tiết lỗi nội bộ cho client
public class ExceptionMappingServerInterceptor implements ServerInterceptor {

  private static final Logger LOG =
      LoggerFactory.getLogger(ExceptionMappingServerInterceptor.class);

  // Bọc listener để bắt exception ném ra từ handler và đóng call bằng status tương ứng
  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    return new SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {
      // Bắt exception khi xử lý message
      @Override
      public void onMessage(ReqT message) {
        try {
          super.onMessage(message);
        } catch (RuntimeException e) {
          fail(call, e);
        }
      }

      // Bắt exception khi handler unary chạy
      @Override
      public void onHalfClose() {
        try {
          super.onHalfClose();
        } catch (RuntimeException e) {
          fail(call, e);
        }
      }

      // Bắt exception khi xử lý ready
      @Override
      public void onReady() {
        try {
          super.onReady();
        } catch (RuntimeException e) {
          fail(call, e);
        }
      }
    };
  }

  // Đóng call bằng status: giữ nguyên status có sẵn, IAE thành INVALID_ARGUMENT, còn lại INTERNAL
  private static void fail(ServerCall<?, ?> call, RuntimeException e) {
    if (e instanceof StatusRuntimeException status) {
      Metadata trailers = Status.trailersFromThrowable(status);
      call.close(status.getStatus(), trailers == null ? new Metadata() : trailers);
    } else if (e instanceof IllegalArgumentException) {
      call.close(Status.INVALID_ARGUMENT.withDescription("invalid argument"), new Metadata());
    } else {
      LOG.error("gRPC handler failed", e);
      call.close(Status.INTERNAL.withDescription("internal error"), new Metadata());
    }
  }
}
