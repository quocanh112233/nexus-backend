package com.nexus.identity.interfaces.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.grpc.GrpcAuth;
import com.nexus.common.grpc.GrpcStatuses;
import com.nexus.identity.IdentityIntegrationTest;
import com.nexus.identity.application.port.TokenIssuer;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.MediaRef;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.UserRepository;
import com.nexus.identity.domain.user.Username;
import com.nexus.proto.identity.v1.BatchGetUsersRequest;
import com.nexus.proto.identity.v1.BatchGetUsersResponse;
import com.nexus.proto.identity.v1.GetUserRequest;
import com.nexus.proto.identity.v1.UserServiceGrpc;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Kiểm tra UserService qua gRPC thật, xác thực JWT của Identity và Postgres thật
@SpringBootTest
class UserGrpcServiceIT extends IdentityIntegrationTest {

  private static final String MESSAGING_TOKEN = "it-messaging-token";

  @Autowired private UserRepository users;
  @Autowired private TokenIssuer tokenIssuer;
  @Autowired private GrpcServerLifecycle grpcServer;

  private ManagedChannel channel;

  // Đóng kênh gRPC client sau mỗi test
  @AfterEach
  void closeChannel() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  // Kịch bản: lấy thông tin công khai của một user có avatar
  // Input: GetUser với user_id của user đã lưu, kèm Bearer hợp lệ
  // Output: OK, user_id, username, display_name, avatar_url đúng giá trị đã lưu
  @Test
  void getUserReturnsPublicFields() {
    User saved = users.save(newUser("Alice", new MediaRef("p/a", "https://img/a.png")));

    var user =
        stub(saved).getUser(GetUserRequest.newBuilder().setUserId(id(saved)).build()).getUser();

    assertThat(user.getUserId()).isEqualTo(id(saved));
    assertThat(user.getUsername()).isEqualTo(saved.username().value());
    assertThat(user.getDisplayName()).isEqualTo("Alice");
    assertThat(user.getAvatarUrl()).isEqualTo("https://img/a.png");
  }

  // Kịch bản: user chưa có avatar
  // Input: GetUser của user không có avatar
  // Output: avatar_url là chuỗi rỗng
  @Test
  void missingAvatarIsEmptyString() {
    User saved = users.save(newUser("Bob", null));

    var user =
        stub(saved).getUser(GetUserRequest.newBuilder().setUserId(id(saved)).build()).getUser();

    assertThat(user.getAvatarUrl()).isEmpty();
  }

  // Kịch bản: user không tồn tại
  // Input: GetUser với user_id UUID ngẫu nhiên
  // Output: NOT_FOUND kèm ErrorInfo reason NOT_FOUND
  @Test
  void getUserUnknownIsNotFound() {
    User caller = users.save(newUser("Carol", null));

    assertThatThrownBy(
            () ->
                stub(caller)
                    .getUser(
                        GetUserRequest.newBuilder()
                            .setUserId(UUID.randomUUID().toString())
                            .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
              assertThat(GrpcStatuses.reasonOf(e)).contains("NOT_FOUND");
            });
  }

  // Kịch bản: lấy lô gồm user có thật và id lạ
  // Input: BatchGetUsers với 2 id có thật và 1 id ngẫu nhiên
  // Output: OK, chỉ trả 2 user tồn tại
  @Test
  void batchGetReturnsOnlyExistingUsers() {
    User a = users.save(newUser("Dan", null));
    User b = users.save(newUser("Eve", null));

    BatchGetUsersResponse response =
        stub(a)
            .batchGetUsers(
                BatchGetUsersRequest.newBuilder()
                    .addUserIds(id(a))
                    .addUserIds(id(b))
                    .addUserIds(UUID.randomUUID().toString())
                    .build());

    assertThat(response.getUsersList())
        .extracting(u -> u.getUserId())
        .containsExactlyInAnyOrder(id(a), id(b));
  }

  // Kịch bản: lô vượt giới hạn 100
  // Input: BatchGetUsers với 101 id
  // Output: INVALID_ARGUMENT
  @Test
  void batchGetMoreThan100IsInvalidArgument() {
    User caller = users.save(newUser("Fay", null));
    BatchGetUsersRequest.Builder request = BatchGetUsersRequest.newBuilder();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      ids.add(UUID.randomUUID().toString());
    }
    request.addAllUserIds(ids);

    assertThatThrownBy(() -> stub(caller).batchGetUsers(request.build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  // Kịch bản: đúng 100 id là biên cho phép
  // Input: BatchGetUsers với 100 id ngẫu nhiên
  // Output: OK, danh sách rỗng
  @Test
  void batchGetExactly100IsAllowed() {
    User caller = users.save(newUser("Gus", null));
    BatchGetUsersRequest.Builder request = BatchGetUsersRequest.newBuilder();
    for (int i = 0; i < 100; i++) {
      request.addUserIds(UUID.randomUUID().toString());
    }

    assertThat(stub(caller).batchGetUsers(request.build()).getUsersList()).isEmpty();
  }

  // Kịch bản: id gửi lên không phải UUID
  // Input: GetUser với user_id "not-a-uuid" và BatchGetUsers chứa "not-a-uuid"
  // Output: cả hai INVALID_ARGUMENT
  @Test
  void malformedUuidIsInvalidArgument() {
    User caller = users.save(newUser("Hal", null));

    assertThatThrownBy(
            () -> stub(caller).getUser(GetUserRequest.newBuilder().setUserId("not-a-uuid").build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    assertThatThrownBy(
            () ->
                stub(caller)
                    .batchGetUsers(
                        BatchGetUsersRequest.newBuilder().addUserIds("not-a-uuid").build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  // Kịch bản: gọi không kèm token
  // Input: GetUser không có header authorization
  // Output: UNAUTHENTICATED
  @Test
  void noTokenIsUnauthenticated() {
    channel = plaintextChannel(null);

    assertThatThrownBy(
            () ->
                UserServiceGrpc.newBlockingStub(channel)
                    .getUser(
                        GetUserRequest.newBuilder()
                            .setUserId(UUID.randomUUID().toString())
                            .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
  }

  // Kịch bản: messaging-service gọi GetUser bằng service token hợp lệ
  // Input: GetUser kèm x-service-token của caller messaging-service, không có Bearer
  // Output: OK, trả đúng user_id
  @Test
  void serviceTokenCanCallGetUser() {
    User saved = users.save(newUser("Ivy", null));
    channel = serviceChannel(MESSAGING_TOKEN);

    var user =
        UserServiceGrpc.newBlockingStub(channel)
            .getUser(GetUserRequest.newBuilder().setUserId(id(saved)).build())
            .getUser();

    assertThat(user.getUserId()).isEqualTo(id(saved));
  }

  // Kịch bản: service token sai
  // Input: GetUser kèm x-service-token không thuộc caller nào
  // Output: UNAUTHENTICATED
  @Test
  void wrongServiceTokenIsUnauthenticated() {
    channel = serviceChannel("not-the-token");

    assertThatThrownBy(
            () ->
                UserServiceGrpc.newBlockingStub(channel)
                    .getUser(
                        GetUserRequest.newBuilder()
                            .setUserId(UUID.randomUUID().toString())
                            .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
  }

  // Kịch bản: caller hợp lệ gọi RPC ngoài allowlist
  // Input: BatchGetUsers kèm service token đúng của messaging-service
  // Output: PERMISSION_DENIED
  @Test
  void serviceTokenCannotCallOtherRpc() {
    channel = serviceChannel(MESSAGING_TOKEN);

    assertThatThrownBy(
            () ->
                UserServiceGrpc.newBlockingStub(channel)
                    .batchGetUsers(BatchGetUsersRequest.newBuilder().build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  // Đặt token của caller messaging-service cho test (yml mặc định để trống)
  @DynamicPropertySource
  static void messagingCaller(DynamicPropertyRegistry registry) {
    registry.add("app.grpc.service-callers.messaging-service.token", () -> MESSAGING_TOKEN);
  }

  // Kênh plaintext gắn header x-service-token, thay kênh cũ
  private ManagedChannel serviceChannel(String token) {
    if (channel != null) {
      channel.shutdownNow();
    }
    return ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort())
        .usePlaintext()
        .intercept(serviceTokenInterceptor(token))
        .build();
  }

  // Interceptor client gắn header x-service-token vào mọi call
  private static ClientInterceptor serviceTokenInterceptor(String value) {
    return new ClientInterceptor() {
      // Thêm header x-service-token trước khi gửi call
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
        return new SimpleForwardingClientCall<>(next.newCall(method, options)) {
          // Gắn header rồi start call
          @Override
          public void start(Listener<RespT> listener, Metadata headers) {
            headers.put(GrpcAuth.SERVICE_TOKEN, value);
            super.start(listener, headers);
          }
        };
      }
    };
  }

  // Id user dạng chuỗi
  private static String id(User user) {
    return user.id().value().toString();
  }

  // Dựng user mới với username ngẫu nhiên và avatar tùy chọn
  private static User newUser(String displayName, MediaRef avatar) {
    String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    User user =
        User.register(
            UserId.newId(),
            new Email(tag + "@x.com"),
            new Username(tag),
            new DisplayName(displayName),
            new PasswordHash("$2a$12$hash"),
            now);
    if (avatar != null) {
      user.changeAvatar(avatar, now);
    }
    return user;
  }

  // Stub blocking gắn Bearer do Identity phát cho user, mở kênh mới thay kênh cũ
  private UserServiceGrpc.UserServiceBlockingStub stub(User caller) {
    channel = plaintextChannel("Bearer " + tokenIssuer.issue(caller).value());
    return UserServiceGrpc.newBlockingStub(channel);
  }

  // Kênh plaintext tới cổng gRPC thật, header authorization null thì không gắn
  private ManagedChannel plaintextChannel(String authorization) {
    if (channel != null) {
      channel.shutdownNow();
    }
    ManagedChannelBuilder<?> builder =
        ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext();
    if (authorization != null) {
      builder.intercept(authorizationInterceptor(authorization));
    }
    return builder.build();
  }

  // Interceptor client gắn header authorization vào mọi call
  private static ClientInterceptor authorizationInterceptor(String value) {
    return new ClientInterceptor() {
      // Thêm header authorization trước khi gửi call
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
        return new SimpleForwardingClientCall<>(next.newCall(method, options)) {
          // Gắn header rồi start call
          @Override
          public void start(Listener<RespT> listener, Metadata headers) {
            headers.put(GrpcAuth.AUTHORIZATION, value);
            super.start(listener, headers);
          }
        };
      }
    };
  }
}
