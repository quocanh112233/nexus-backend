package com.nexus.community.interfaces.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.grpc.GrpcAuth;
import com.nexus.community.CommunityIntegrationTest;
import com.nexus.proto.community.v1.CheckServerMembershipRequest;
import com.nexus.proto.community.v1.ListServerIdsForUserRequest;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra PermissionService với caller là service (x-service-token) trên gRPC và Postgres thật
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "app.grpc.service-callers.realtime-gateway.token=dummy-realtime",
      "app.grpc.service-callers.presence-service.token=dummy-presence",
      "app.grpc.service-callers.messaging-service.token=dummy-messaging"
    })
class PermissionGrpcServiceServiceTokenTest extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int httpPort;
  @Autowired private GrpcServerLifecycle grpcServer;

  private ManagedChannel channel;

  // Đóng kênh gRPC client sau mỗi test
  @AfterEach
  void closeChannel() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  // Kịch bản: realtime-gateway hỏi tư cách thành viên của user bất kỳ
  // Input: token realtime, CheckServerMembership cho user X là member và user Y không phải
  // Output: is_member true cho X, false cho Y
  @Test
  void serviceCallerMayAskAboutAnyUser() {
    UUID server = createServer();
    UUID member = joined(server);

    var stub = stub(header(GrpcAuth.SERVICE_TOKEN, "dummy-realtime"));

    assertThat(stub.checkServerMembership(membership(member, server)).getIsMember()).isTrue();
    assertThat(stub.checkServerMembership(membership(UUID.randomUUID(), server)).getIsMember())
        .isFalse();
  }

  // Kịch bản: user thường hỏi về user khác vẫn bị chặn
  // Input: Bearer của user A, CheckServerMembership với user_id của B
  // Output: PERMISSION_DENIED
  @Test
  void userCallerStillRestrictedToSelf() {
    UUID server = createServer();
    var stub = stub(header(GrpcAuth.AUTHORIZATION, bearer(UUID.randomUUID())));

    assertThatThrownBy(() -> stub.checkServerMembership(membership(joined(server), server)))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  // Kịch bản: presence-service liệt kê server của user
  // Input: token presence, ListServerIdsForUser cho user là member của 2 trong 3 server
  // Output: đúng 2 server_id của user, không có server thứ ba
  @Test
  void listServerIdsReturnsOnlyMembership() {
    UUID first = createServer();
    UUID second = createServer();
    createServer();
    UUID user = UUID.randomUUID();
    join(first, user);
    join(second, user);

    var response =
        stub(header(GrpcAuth.SERVICE_TOKEN, "dummy-presence"))
            .listServerIdsForUser(
                ListServerIdsForUserRequest.newBuilder().setUserId(user.toString()).build());

    assertThat(response.getServerIdsList())
        .containsExactlyInAnyOrder(first.toString(), second.toString());
  }

  // Kịch bản: user thường liệt kê server của user khác
  // Input: Bearer của user A, ListServerIdsForUser với user_id của B
  // Output: PERMISSION_DENIED
  @Test
  void userListingAnotherUsersServersIsDenied() {
    var stub = stub(header(GrpcAuth.AUTHORIZATION, bearer(UUID.randomUUID())));
    ListServerIdsForUserRequest request =
        ListServerIdsForUserRequest.newBuilder().setUserId(UUID.randomUUID().toString()).build();

    assertThatThrownBy(() -> stub.listServerIdsForUser(request))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  // Kịch bản: caller không được cấp method đó
  // Input: token messaging gọi CheckServerMembership, token realtime gọi ListServerIdsForUser
  // Output: cả hai PERMISSION_DENIED
  @Test
  void methodsOutsideAllowlistAreDenied() {
    var messaging = stub(header(GrpcAuth.SERVICE_TOKEN, "dummy-messaging"));
    assertThatThrownBy(
            () ->
                messaging.checkServerMembership(CheckServerMembershipRequest.getDefaultInstance()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));

    var realtime = stub(header(GrpcAuth.SERVICE_TOKEN, "dummy-realtime"));
    assertThatThrownBy(
            () -> realtime.listServerIdsForUser(ListServerIdsForUserRequest.getDefaultInstance()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  // Kịch bản: messaging-service hỏi quyền kênh thay cho user bằng service token
  // Input: token messaging, CheckChannelPermissions cho user là member của server
  // Output: OK, server_id đúng và có quyền VIEW_CHANNEL
  @Test
  void messagingMayCheckChannelPermissionsForAnyUser() {
    UUID server = createServer();
    UUID member = joined(server);
    String channel =
        jdbc.queryForObject(
            "select id::text from community.channels where server_id = ?", String.class, server);

    var response =
        stub(header(GrpcAuth.SERVICE_TOKEN, "dummy-messaging"))
            .checkChannelPermissions(
                com.nexus.proto.community.v1.CheckChannelPermissionsRequest.newBuilder()
                    .setUserId(member.toString())
                    .setChannelId(channel)
                    .build());

    assertThat(response.getServerId()).isEqualTo(server.toString());
    assertThat(response.getGrantedList())
        .contains(com.nexus.proto.community.v1.Permission.PERMISSION_VIEW_CHANNEL);
  }

  // Dựng metadata một header
  private static Metadata header(Metadata.Key<String> key, String value) {
    Metadata headers = new Metadata();
    headers.put(key, value);
    return headers;
  }

  // Stub blocking tới cổng gRPC thật, mang sẵn metadata cho trước
  private PermissionServiceGrpc.PermissionServiceBlockingStub stub(Metadata headers) {
    channel =
        ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
    return PermissionServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  // Dựng request CheckServerMembership
  private static CheckServerMembershipRequest membership(UUID user, UUID server) {
    return CheckServerMembershipRequest.newBuilder()
        .setUserId(user.toString())
        .setServerId(server.toString())
        .build();
  }

  // Tạo server qua REST bằng một chủ mới (có kênh general và role mặc định) và trả id
  private UUID createServer() {
    try {
      HttpResponse<String> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://localhost:" + httpPort + "/api/v1/servers"))
                      .header("Authorization", bearer(UUID.randomUUID()))
                      .header("Content-Type", "application/json")
                      .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Mine\"}"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      return UUID.fromString(MAPPER.readTree(response.body()).get("id").asText());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // Thêm user vào server bằng JDBC
  private void join(UUID server, UUID user) {
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at) values (?, ?, now())",
        server,
        user);
  }

  // Tạo user mới và cho vào server
  private UUID joined(UUID server) {
    UUID user = UUID.randomUUID();
    join(server, user);
    return user;
  }
}
