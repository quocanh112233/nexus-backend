package com.nexus.messaging.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.ChannelPermission;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.proto.community.v1.CheckChannelPermissionsRequest;
import com.nexus.proto.community.v1.CheckChannelPermissionsResponse;
import com.nexus.proto.community.v1.Permission;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Kiểm tra client gRPC hỏi quyền kênh với server Community giả chạy in-process
class GrpcChannelPermissionCheckerTest {

  private static final UserId USER = new UserId(UUID.randomUUID());
  private static final ChannelId CHANNEL = new ChannelId(UUID.randomUUID());

  private final FakePermissionService fake = new FakePermissionService();
  private Server server;
  private ManagedChannel channel;
  private CircuitBreaker breaker;
  private GrpcChannelPermissionChecker checker;

  // Dựng server giả, kênh có retryPolicy, breaker mặc định và checker deadline 300 ms
  @BeforeEach
  void setUp() throws Exception {
    String name = "community-" + UUID.randomUUID();
    server = InProcessServerBuilder.forName(name).addService(fake).build().start();
    channel =
        CommunityChannelConfig.configure(
                InProcessChannelBuilder.forName(name), ObservationRegistry.NOOP)
            .build();
    useBreaker(Duration.ofSeconds(10));
  }

  // Tắt kênh và server sau mỗi test
  @AfterEach
  void tearDown() throws Exception {
    channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
  }

  // Tạo breaker "community" với thời gian mở cho trước rồi dựng checker quanh nó
  private void useBreaker(Duration waitInOpen) {
    breaker =
        CircuitBreaker.of(
            "community", CommunityChannelConfig.breakerConfig(20, 50f, waitInOpen, 3));
    checker = new GrpcChannelPermissionChecker(channel, breaker, Duration.ofMillis(300));
  }

  // Gọi check n lần và nuốt mọi ngoại lệ
  private void callIgnoringErrors(int n) {
    for (int i = 0; i < n; i++) {
      try {
        checker.check(USER, CHANNEL);
      } catch (RuntimeException ignored) {
      }
    }
  }

  // Kịch bản: Community trả server và hai quyền VIEW, SEND
  // Input: response server_id cụ thể, granted=[VIEW_CHANNEL, SEND_MESSAGES]
  // Output: ChannelAccess có đúng serverId và đúng hai quyền, request mang đúng user và channel
  @Test
  void okResponseMapsServerIdAndGranted() {
    UUID serverId = UUID.randomUUID();
    fake.response =
        CheckChannelPermissionsResponse.newBuilder()
            .setServerId(serverId.toString())
            .addGranted(Permission.PERMISSION_VIEW_CHANNEL)
            .addGranted(Permission.PERMISSION_SEND_MESSAGES)
            .build();

    ChannelAccess access = checker.check(USER, CHANNEL);

    assertThat(access.serverId()).isEqualTo(serverId);
    assertThat(access.granted())
        .containsExactlyInAnyOrder(ChannelPermission.VIEW_CHANNEL, ChannelPermission.SEND_MESSAGES);
    assertThat(fake.lastRequest.get().getUserId()).isEqualTo(USER.value().toString());
    assertThat(fake.lastRequest.get().getChannelId()).isEqualTo(CHANNEL.value().toString());
  }

  // Kịch bản: user không phải member nên granted rỗng
  // Input: response với granted rỗng
  // Output: ChannelAccess không có quyền nào
  @Test
  void emptyGrantedMapsToNoPermissions() {
    fake.response =
        CheckChannelPermissionsResponse.newBuilder()
            .setServerId(UUID.randomUUID().toString())
            .build();

    assertThat(checker.check(USER, CHANNEL).granted()).isEmpty();
  }

  // Kịch bản: Community báo kênh không tồn tại
  // Input: server trả NOT_FOUND
  // Output: ChannelNotFoundException
  @Test
  void notFoundThrowsChannelNotFound() {
    fake.failWith = Status.NOT_FOUND;

    assertThatThrownBy(() -> checker.check(USER, CHANNEL))
        .isInstanceOf(ChannelNotFoundException.class);
  }

  // Kịch bản: lỗi nghiệp vụ NOT_FOUND không được tính vào tỉ lệ lỗi của breaker
  // Input: 25 call liên tiếp, server trả NOT_FOUND
  // Output: breaker vẫn CLOSED
  @Test
  void circuitStaysClosedAfter25NotFound() {
    fake.failWith = Status.NOT_FOUND;

    callIgnoringErrors(25);

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(fake.calls.get()).isEqualTo(25);
  }

  // Kịch bản: server chậm 1 s trong khi deadline là 300 ms
  // Input: server ngủ 1000 ms, đã làm nóng kênh bằng một call nhanh
  // Output: DependencyUnavailableException trong dưới 400 ms
  @Test
  void slowServerThrowsDependencyUnavailableWithin400ms() {
    fake.response =
        CheckChannelPermissionsResponse.newBuilder()
            .setServerId(UUID.randomUUID().toString())
            .build();
    checker.check(USER, CHANNEL);
    fake.delayMillis = 1000;

    long start = System.nanoTime();
    assertThatThrownBy(() -> checker.check(USER, CHANNEL))
        .isInstanceOf(DependencyUnavailableException.class);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(elapsed).isLessThan(Duration.ofMillis(400));
  }

  // Kịch bản: server luôn UNAVAILABLE, channel retry tối đa 2 lần
  // Input: server trả UNAVAILABLE mọi call
  // Output: đúng 3 call tới server rồi DependencyUnavailableException
  @Test
  void unavailableIsRetriedTwiceThenFails() {
    fake.failWith = Status.UNAVAILABLE;

    assertThatThrownBy(() -> checker.check(USER, CHANNEL))
        .isInstanceOf(DependencyUnavailableException.class);

    assertThat(fake.calls.get()).isEqualTo(3);
  }

  // Kịch bản: DEADLINE_EXCEEDED không nằm trong retryableStatusCodes
  // Input: server trả DEADLINE_EXCEEDED
  // Output: đúng 1 call tới server rồi DependencyUnavailableException
  @Test
  void deadlineExceededIsNotRetried() {
    fake.failWith = Status.DEADLINE_EXCEEDED;

    assertThatThrownBy(() -> checker.check(USER, CHANNEL))
        .isInstanceOf(DependencyUnavailableException.class);

    assertThat(fake.calls.get()).isEqualTo(1);
  }

  // Kịch bản: lỗi hạ tầng liên tiếp làm mạch mở và chặn call kế tiếp
  // Input: 20 call server trả INTERNAL, rồi một call nữa
  // Output: breaker OPEN, call thứ 21 không tới server, ném DependencyUnavailableException < 50 ms
  @Test
  void tenConsecutiveFailuresOpenCircuit() {
    fake.failWith = Status.INTERNAL;
    callIgnoringErrors(20);
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    long start = System.nanoTime();
    assertThatThrownBy(() -> checker.check(USER, CHANNEL))
        .isInstanceOf(DependencyUnavailableException.class);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(fake.calls.get()).isEqualTo(20);
    assertThat(elapsed).isLessThan(Duration.ofMillis(50));
  }

  // Kịch bản: hết thời gian mở, ba call thăm dò thành công thì đóng mạch
  // Input: mạch mở sau 20 INTERNAL, waitDuration 100 ms, server hồi phục, 3 call
  // Output: breaker CLOSED, cả 3 call tới server
  @Test
  void halfOpenAfterWaitAllowsThreeProbeCalls() throws Exception {
    useBreaker(Duration.ofMillis(100));
    fake.failWith = Status.INTERNAL;
    callIgnoringErrors(20);
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    Thread.sleep(250);
    fake.failWith = null;
    fake.response =
        CheckChannelPermissionsResponse.newBuilder()
            .setServerId(UUID.randomUUID().toString())
            .build();

    for (int i = 0; i < 3; i++) {
      checker.check(USER, CHANNEL);
    }

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(fake.calls.get()).isEqualTo(23);
  }

  // Kịch bản: Community từ chối token của messaging là lỗi cấu hình, không phải câu trả lời quyền
  // Input: server trả PERMISSION_DENIED
  // Output: DependencyUnavailableException, breaker không ghi nhận lỗi
  @Test
  void permissionDeniedFromCommunityIsDependencyUnavailable() {
    fake.failWith = Status.PERMISSION_DENIED;

    callIgnoringErrors(25);

    assertThatThrownBy(() -> checker.check(USER, CHANNEL))
        .isInstanceOf(DependencyUnavailableException.class);
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  // Kịch bản: Community trả server_id rỗng hoặc hỏng
  // Input: response thành công nhưng server_id rỗng
  // Output: DependencyUnavailableException thay vì IllegalArgumentException
  @Test
  void malformedServerIdIsDependencyUnavailable() {
    fake.response = CheckChannelPermissionsResponse.newBuilder().setServerId("").build();

    assertThatThrownBy(() -> checker.check(USER, CHANNEL))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  // Kịch bản: trạng thái breaker được xuất ra Micrometer cho Grafana
  // Input: breaker mới đăng ký vào SimpleMeterRegistry
  // Output: gauge resilience4j.circuitbreaker.state của trạng thái closed bằng 1, open bằng 0
  @Test
  void breakerStateIsExposedAsMicrometerGauge() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    CommunityChannelConfig.bindState(breaker, registry);

    assertThat(
            registry
                .get("resilience4j.circuitbreaker.state")
                .tags("name", "community", "state", "closed")
                .gauge()
                .value())
        .isEqualTo(1.0);
    assertThat(
            registry
                .get("resilience4j.circuitbreaker.state")
                .tags("name", "community", "state", "open")
                .gauge()
                .value())
        .isEqualTo(0.0);
  }

  // Server giả đếm call và điều khiển được trạng thái, độ trễ, nội dung trả về
  private static final class FakePermissionService
      extends PermissionServiceGrpc.PermissionServiceImplBase {

    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<CheckChannelPermissionsRequest> lastRequest = new AtomicReference<>();
    volatile Status failWith;
    volatile long delayMillis;
    volatile CheckChannelPermissionsResponse response =
        CheckChannelPermissionsResponse.getDefaultInstance();

    // Đếm call, ngủ theo độ trễ rồi trả lỗi hoặc response đã cấu hình
    @Override
    public void checkChannelPermissions(
        CheckChannelPermissionsRequest request,
        StreamObserver<CheckChannelPermissionsResponse> observer) {
      calls.incrementAndGet();
      lastRequest.set(request);
      try {
        if (delayMillis > 0) {
          Thread.sleep(delayMillis);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (failWith != null) {
        observer.onError(failWith.asRuntimeException());
        return;
      }
      observer.onNext(response);
      observer.onCompleted();
    }
  }
}
