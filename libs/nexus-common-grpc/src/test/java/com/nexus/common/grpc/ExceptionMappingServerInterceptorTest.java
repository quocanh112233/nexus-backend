package com.nexus.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.proto.community.v1.CheckServerMembershipRequest;
import com.nexus.proto.community.v1.CheckServerMembershipResponse;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

// Kiểm thử ExceptionMappingServerInterceptor trên server gRPC in-process
class ExceptionMappingServerInterceptorTest {

  // Gọi một RPC mà handler ném đúng exception cho trước, trả lỗi client nhận được
  private StatusRuntimeException callThrowing(Supplier<RuntimeException> thrown) {
    PermissionServiceGrpc.PermissionServiceImplBase service =
        new PermissionServiceGrpc.PermissionServiceImplBase() {
          // Handler giả luôn ném exception cho trước
          @Override
          public void checkServerMembership(
              CheckServerMembershipRequest request,
              StreamObserver<CheckServerMembershipResponse> observer) {
            throw thrown.get();
          }
        };
    try (GrpcTestServer server =
        new GrpcTestServer(new ExceptionMappingServerInterceptor(), null, service)) {
      Throwable error =
          org.assertj.core.api.Assertions.catchThrowable(
              () ->
                  PermissionServiceGrpc.newBlockingStub(server.channel())
                      .checkServerMembership(CheckServerMembershipRequest.getDefaultInstance()));
      assertThat(error).isInstanceOf(StatusRuntimeException.class);
      return (StatusRuntimeException) error;
    }
  }

  // Kịch bản: handler ném IllegalArgumentException
  // Input: IllegalArgumentException("bad")
  // Output: INVALID_ARGUMENT với description cố định "invalid argument"
  @Test
  void illegalArgumentBecomesInvalidArgument() {
    StatusRuntimeException e = callThrowing(() -> new IllegalArgumentException("bad"));
    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(e.getStatus().getDescription()).isEqualTo("invalid argument");
  }

  // Kịch bản: lỗi không mong đợi không được lộ chi tiết
  // Input: IllegalStateException("secret stack")
  // Output: INTERNAL với description cố định "internal error"
  @Test
  void unexpectedErrorBecomesInternalWithoutLeak() {
    StatusRuntimeException e = callThrowing(() -> new IllegalStateException("secret stack"));
    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(e.getStatus().getDescription()).isEqualTo("internal error");
  }

  // Kịch bản: StatusRuntimeException do handler ném được giữ nguyên cùng ErrorInfo
  // Input: GrpcStatuses.error(NOT_FOUND, "nexus.community", "NOT_FOUND", "x")
  // Output: NOT_FOUND và reasonOf bằng Optional.of("NOT_FOUND")
  @Test
  void statusExceptionIsKept() {
    StatusRuntimeException e =
        callThrowing(
            () -> GrpcStatuses.error(Status.Code.NOT_FOUND, "nexus.community", "NOT_FOUND", "x"));
    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(GrpcStatuses.reasonOf(e)).isEqualTo(Optional.of("NOT_FOUND"));
  }

  // Kịch bản: lỗi không có ErrorInfo thì reasonOf rỗng
  // Input: StatusRuntimeException(Status.INTERNAL) thuần
  // Output: Optional.empty()
  @Test
  void reasonOfPlainStatusIsEmpty() {
    assertThatThrownBy(
            () -> {
              throw new StatusRuntimeException(Status.INTERNAL);
            })
        .isInstanceOfSatisfying(
            StatusRuntimeException.class, e -> assertThat(GrpcStatuses.reasonOf(e)).isEmpty());
  }
}
