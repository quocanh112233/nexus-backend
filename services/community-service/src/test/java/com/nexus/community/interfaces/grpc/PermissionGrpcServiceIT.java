package com.nexus.community.interfaces.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.grpc.GrpcAuth;
import com.nexus.common.grpc.GrpcStatuses;
import com.nexus.community.CommunityIntegrationTest;
import com.nexus.proto.community.v1.CheckChannelPermissionsRequest;
import com.nexus.proto.community.v1.CheckChannelPermissionsResponse;
import com.nexus.proto.community.v1.CheckServerMembershipRequest;
import com.nexus.proto.community.v1.Permission;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra PermissionService qua gRPC thật trên cổng ngẫu nhiên, xác thực JWT và Postgres thật
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PermissionGrpcServiceIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private GrpcServerLifecycle grpcServer;
  @LocalServerPort private int httpPort;

  private ManagedChannel channel;

  // Đóng kênh gRPC client sau mỗi test
  @AfterEach
  void closeChannel() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  // Kịch bản: member thường hỏi quyền trên kênh general
  // Input: CheckChannelPermissions với user_id trùng subject JWT, kênh của server vừa tạo
  // Output: OK, server_id đúng, granted theo thứ tự enum đúng 5 quyền mặc định @everyone
  @Test
  void grantedListMatchesEffectivePermissions() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String server = createServer(owner);
    join(server, member);

    CheckChannelPermissionsResponse response =
        stub(member).checkChannelPermissions(channelRequest(member, generalChannel(server)));

    assertThat(response.getServerId()).isEqualTo(server);
    assertThat(response.getGrantedList())
        .containsExactly(
            Permission.PERMISSION_VIEW_CHANNEL,
            Permission.PERMISSION_SEND_MESSAGES,
            Permission.PERMISSION_READ_MESSAGE_HISTORY,
            Permission.PERMISSION_ATTACH_FILES,
            Permission.PERMISSION_ADD_REACTIONS);
  }

  // Kịch bản: người không phải thành viên hỏi quyền trên kênh
  // Input: CheckChannelPermissions của user chưa tham gia server
  // Output: OK, granted rỗng, server_id vẫn đúng
  @Test
  void nonMemberGetsEmptyGrantedWithOk() throws Exception {
    UUID stranger = UUID.randomUUID();
    String server = createServer(UUID.randomUUID());

    CheckChannelPermissionsResponse response =
        stub(stranger).checkChannelPermissions(channelRequest(stranger, generalChannel(server)));

    assertThat(response.getGrantedList()).isEmpty();
    assertThat(response.getServerId()).isEqualTo(server);
  }

  // Kịch bản: kênh không tồn tại
  // Input: CheckChannelPermissions với channel_id UUID ngẫu nhiên
  // Output: NOT_FOUND kèm ErrorInfo reason NOT_FOUND
  @Test
  void unknownChannelIsNotFoundWithReason() {
    UUID user = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                stub(user)
                    .checkChannelPermissions(channelRequest(user, UUID.randomUUID().toString())))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
              assertThat(GrpcStatuses.reasonOf(e)).contains("NOT_FOUND");
            });
  }

  // Kịch bản: id gửi lên không phải UUID
  // Input: CheckChannelPermissions với channel_id "not-a-uuid"
  // Output: INVALID_ARGUMENT
  @Test
  void malformedUuidIsInvalidArgument() {
    UUID user = UUID.randomUUID();

    assertThatThrownBy(() -> stub(user).checkChannelPermissions(channelRequest(user, "not-a-uuid")))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  // Kịch bản: user_id trong request khác subject của JWT
  // Input: CheckChannelPermissions và CheckServerMembership hỏi hộ user khác
  // Output: cả hai PERMISSION_DENIED
  @Test
  void userIdDifferentFromJwtSubjectIsPermissionDenied() throws Exception {
    UUID caller = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    String server = createServer(other);
    String channelId = generalChannel(server);

    assertThatThrownBy(() -> stub(caller).checkChannelPermissions(channelRequest(other, channelId)))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
    assertThatThrownBy(() -> stub(caller).checkServerMembership(membershipRequest(other, server)))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  // Kịch bản: gọi không kèm token
  // Input: CheckServerMembership không có header authorization
  // Output: UNAUTHENTICATED
  @Test
  void noTokenIsUnauthenticated() {
    UUID user = UUID.randomUUID();
    channel = plaintextChannel(null);

    assertThatThrownBy(
            () ->
                PermissionServiceGrpc.newBlockingStub(channel)
                    .checkServerMembership(membershipRequest(user, UUID.randomUUID().toString())))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
  }

  // Kịch bản: token đã hết hạn
  // Input: Bearer token có exp cách đây 1 giờ
  // Output: UNAUTHENTICATED
  @Test
  void expiredTokenIsUnauthenticated() {
    UUID user = UUID.randomUUID();
    String expired = "Bearer " + JWT.token(user, "expired-user", Instant.now().minusSeconds(3600));
    channel = plaintextChannel(expired);

    assertThatThrownBy(
            () ->
                PermissionServiceGrpc.newBlockingStub(channel)
                    .checkServerMembership(membershipRequest(user, UUID.randomUUID().toString())))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
  }

  // Kịch bản: kiểm tra tư cách thành viên của chính người gọi
  // Input: CheckServerMembership của chủ server, rồi của người lạ trên cùng server
  // Output: is_member true cho chủ, false cho người lạ và không phải lỗi
  @Test
  void checkServerMembershipTrueForMemberFalseForStranger() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    String server = createServer(owner);

    boolean ownerIs =
        stub(owner).checkServerMembership(membershipRequest(owner, server)).getIsMember();
    boolean strangerIs =
        stub(stranger).checkServerMembership(membershipRequest(stranger, server)).getIsMember();

    assertThat(ownerIs).isTrue();
    assertThat(strangerIs).isFalse();
  }

  // Dựng request CheckChannelPermissions
  private static CheckChannelPermissionsRequest channelRequest(UUID user, String channelId) {
    return CheckChannelPermissionsRequest.newBuilder()
        .setUserId(user.toString())
        .setChannelId(channelId)
        .build();
  }

  // Dựng request CheckServerMembership
  private static CheckServerMembershipRequest membershipRequest(UUID user, String serverId) {
    return CheckServerMembershipRequest.newBuilder()
        .setUserId(user.toString())
        .setServerId(serverId)
        .build();
  }

  // Stub blocking đã gắn Bearer hợp lệ của user, mở kênh mới thay kênh cũ
  private PermissionServiceGrpc.PermissionServiceBlockingStub stub(UUID user) {
    channel = plaintextChannel(bearer(user));
    return PermissionServiceGrpc.newBlockingStub(channel);
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

  // Thêm user vào server bằng JDBC
  private void join(String server, UUID user) {
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at) values (?::uuid, ?, now())",
        server,
        user);
  }

  // Id kênh duy nhất của server vừa tạo
  private String generalChannel(String server) {
    return jdbc.queryForObject(
        "select id::text from community.channels where server_id = ?::uuid", String.class, server);
  }

  // Tạo server qua REST và trả id
  private String createServer(UUID owner) throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + httpPort + "/api/v1/servers"))
                    .header("Authorization", bearer(owner))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Mine\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    JsonNode body = MAPPER.readTree(response.body());
    return body.get("id").asText();
  }
}
