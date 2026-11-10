package com.nexus.messaging.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.shared.DependencyUnavailableException;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.proto.identity.v1.GetUserRequest;
import com.nexus.proto.identity.v1.GetUserResponse;
import com.nexus.proto.identity.v1.User;
import com.nexus.proto.identity.v1.UserServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Kiểm tra client UserDirectory gọi GetUser với server Identity giả chạy in-process
class GrpcUserDirectoryTest {

  private static final UserId USER = new UserId(UUID.randomUUID());

  private final AtomicReference<Status> failWith = new AtomicReference<>();
  private final AtomicReference<GetUserRequest> seen = new AtomicReference<>();
  private volatile long sleepMillis;
  private Server server;
  private ManagedChannel channel;
  private GrpcUserDirectory directory;

  // Dựng server giả, kênh cấu hình như Community và directory deadline 200 ms
  @BeforeEach
  void setUp() throws Exception {
    String name = "identity-" + UUID.randomUUID();
    server = InProcessServerBuilder.forName(name).addService(new FakeUsers()).build().start();
    channel =
        CommunityChannelConfig.configure(
                InProcessChannelBuilder.forName(name), ObservationRegistry.NOOP)
            .build();
    directory = new GrpcUserDirectory(channel, Duration.ofMillis(200));
  }

  // Tắt kênh và server sau mỗi test
  @AfterEach
  void tearDown() throws Exception {
    channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
  }

  // Kịch bản: user có tồn tại ở Identity
  // Input: GetUser trả OK
  // Output: exists true, request mang đúng user_id
  @Test
  void okMeansExists() {
    assertThat(directory.exists(USER)).isTrue();
    assertThat(seen.get().getUserId()).isEqualTo(USER.value().toString());
  }

  // Kịch bản: user không tồn tại
  // Input: GetUser trả NOT_FOUND
  // Output: exists false, không ném lỗi
  @Test
  void notFoundMeansAbsent() {
    failWith.set(Status.NOT_FOUND);

    assertThat(directory.exists(USER)).isFalse();
  }

  // Kịch bản: Identity sập hoặc từ chối credential
  // Input: GetUser trả UNAVAILABLE rồi PERMISSION_DENIED
  // Output: cả hai ném DependencyUnavailableException
  @Test
  void infraErrorsBecomeDependencyUnavailable() {
    failWith.set(Status.UNAVAILABLE);
    assertThatThrownBy(() -> directory.exists(USER))
        .isInstanceOf(DependencyUnavailableException.class);

    failWith.set(Status.PERMISSION_DENIED);
    assertThatThrownBy(() -> directory.exists(USER))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  // Kịch bản: Identity chậm hơn deadline
  // Input: GetUser ngủ 1 giây, deadline 200 ms
  // Output: DependencyUnavailableException, không treo
  @Test
  void slowIdentityHitsDeadline() {
    sleepMillis = 1000;

    assertThatThrownBy(() -> directory.exists(USER))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  // UserService giả: ghi lại request, ngủ rồi trả lỗi hoặc user theo cấu hình
  private final class FakeUsers extends UserServiceGrpc.UserServiceImplBase {

    // Trả OK hoặc status đã cấu hình
    @Override
    public void getUser(GetUserRequest request, StreamObserver<GetUserResponse> observer) {
      seen.set(request);
      try {
        Thread.sleep(sleepMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      Status status = failWith.get();
      if (status != null) {
        observer.onError(status.asRuntimeException());
        return;
      }
      observer.onNext(
          GetUserResponse.newBuilder()
              .setUser(User.newBuilder().setUserId(request.getUserId()))
              .build());
      observer.onCompleted();
    }
  }
}
