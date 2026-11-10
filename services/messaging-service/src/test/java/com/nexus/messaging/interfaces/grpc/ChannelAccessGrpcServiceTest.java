package com.nexus.messaging.interfaces.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.grpc.AuthServerInterceptor;
import com.nexus.common.grpc.GrpcAuth;
import com.nexus.common.grpc.ServiceCaller;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.ChannelPermission;
import com.nexus.messaging.infrastructure.persistence.channel.TombstoneChannelPermissionChecker;
import com.nexus.proto.messaging.v1.ChannelAccessServiceGrpc;
import com.nexus.proto.messaging.v1.CheckChannelAccessRequest;
import com.nexus.proto.messaging.v1.CheckChannelAccessResponse;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

// Kiểm tra ChannelAccessGrpcService qua gRPC in-process với checker giả
class ChannelAccessGrpcServiceTest {

  private static final UUID SERVER = UUID.randomUUID();
  private static final UUID USER = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();
  private static final UUID CHANNEL = UUID.randomUUID();
  private static final String SERVICE_TOKEN = "dummy-realtime";

  private Server server;
  private ManagedChannel channel;

  // Tắt kênh và server sau mỗi test
  @AfterEach
  void tearDown() throws Exception {
    channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
  }

  // Dựng server in-process quanh checker và trả stub, có AuthServerInterceptor, không ánh xạ lỗi
  private ChannelAccessServiceGrpc.ChannelAccessServiceBlockingStub stub(
      ChannelPermissionChecker checker, Metadata headers) throws Exception {
    JwtDecoder decoder =
        token -> Jwt.withTokenValue(token).header("alg", "none").subject(token).build();
    Map<String, ServiceCaller> callers =
        Map.of(
            "realtime-gateway",
            new ServiceCaller(
                SERVICE_TOKEN,
                List.of("nexus.messaging.v1.ChannelAccessService/CheckChannelAccess")));
    String name = "access-" + UUID.randomUUID();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .intercept(new AuthServerInterceptor(decoder, callers))
            .addService(new ChannelAccessGrpcService(checker))
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    return ChannelAccessServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  // Stub gọi bằng service token của realtime-gateway
  private ChannelAccessServiceGrpc.ChannelAccessServiceBlockingStub stub(
      ChannelPermissionChecker checker) throws Exception {
    Metadata headers = new Metadata();
    headers.put(GrpcAuth.SERVICE_TOKEN, SERVICE_TOKEN);
    return stub(checker, headers);
  }

  // Stub gọi bằng JWT của user có subject là userId
  private ChannelAccessServiceGrpc.ChannelAccessServiceBlockingStub userStub(
      ChannelPermissionChecker checker, UUID userId) throws Exception {
    Metadata headers = new Metadata();
    headers.put(GrpcAuth.AUTHORIZATION, "Bearer " + userId);
    return stub(checker, headers);
  }

  // Dựng request với user và kênh cố định
  private static CheckChannelAccessRequest request(String user, String channelId) {
    return CheckChannelAccessRequest.newBuilder().setUserId(user).setChannelId(channelId).build();
  }

  // Dựng request hợp lệ
  private static CheckChannelAccessRequest request() {
    return request(USER.toString(), CHANNEL.toString());
  }

  // Kịch bản: member có VIEW_CHANNEL
  // Input: checker trả ChannelAccess(server, {VIEW_CHANNEL, SEND_MESSAGES})
  // Output: can_view true, server_id đúng, is_dm false
  @Test
  void memberWithViewCanView() throws Exception {
    CheckChannelAccessResponse response =
        stub((u, c) ->
                new ChannelAccess(
                    SERVER,
                    Set.of(ChannelPermission.VIEW_CHANNEL, ChannelPermission.SEND_MESSAGES)))
            .checkChannelAccess(request());

    assertThat(response.getCanView()).isTrue();
    assertThat(response.getServerId()).isEqualTo(SERVER.toString());
    assertThat(response.getIsDm()).isFalse();
  }

  // Kịch bản: người tham gia DM, kể cả khi đã hủy kết bạn vẫn xem được
  // Input: checker trả ChannelAccess(null, {VIEW_CHANNEL, READ_MESSAGE_HISTORY})
  // Output: can_view true, is_dm true, server_id rỗng
  @Test
  void dmParticipantCanViewAsDm() throws Exception {
    CheckChannelAccessResponse response =
        stub((u, c) ->
                new ChannelAccess(
                    null,
                    Set.of(ChannelPermission.VIEW_CHANNEL, ChannelPermission.READ_MESSAGE_HISTORY)))
            .checkChannelAccess(request());

    assertThat(response.getCanView()).isTrue();
    assertThat(response.getIsDm()).isTrue();
    assertThat(response.getServerId()).isEmpty();
  }

  // Kịch bản: user có quyền khác nhưng thiếu VIEW_CHANNEL
  // Input: checker trả ChannelAccess(server, {SEND_MESSAGES})
  // Output: OK, can_view false và không lộ server_id
  @Test
  void noViewPermissionCannotView() throws Exception {
    CheckChannelAccessResponse response =
        stub((u, c) -> new ChannelAccess(SERVER, Set.of(ChannelPermission.SEND_MESSAGES)))
            .checkChannelAccess(request());

    assertThat(response.getCanView()).isFalse();
    assertThat(response.getServerId()).isEmpty();
  }

  // Kịch bản: kênh không tồn tại
  // Input: checker ném ChannelNotFoundException
  // Output: OK với can_view false, không phải NOT_FOUND
  @Test
  void unknownChannelIsOkWithCanViewFalse() throws Exception {
    CheckChannelAccessResponse response =
        stub((u, c) -> {
              throw new ChannelNotFoundException("channel not found");
            })
            .checkChannelAccess(request());

    assertThat(response.getCanView()).isFalse();
  }

  // Kịch bản: kênh đã bị tombstone thì không hỏi Community
  // Input: Tombstone checker với isDeleted=true bọc delegate ném lỗi nếu bị gọi
  // Output: OK với can_view false
  @Test
  void deletedChannelCannotView() throws Exception {
    ChannelPermissionChecker tombstone =
        new TombstoneChannelPermissionChecker(
            new DeletedRefs(),
            (u, c) -> {
              throw new AssertionError("delegate must not be called");
            });

    assertThat(stub(tombstone).checkChannelAccess(request()).getCanView()).isFalse();
  }

  // Kịch bản: Community không trả lời được
  // Input: checker ném DependencyUnavailableException
  // Output: UNAVAILABLE
  @Test
  void dependencyDownIsUnavailable() throws Exception {
    var stub =
        stub(
            (u, c) -> {
              throw new DependencyUnavailableException("down", null);
            });

    assertThatThrownBy(() -> stub.checkChannelAccess(request()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
  }

  // Kịch bản: id sai định dạng
  // Input: user_id không phải UUID
  // Output: INVALID_ARGUMENT
  @Test
  void malformedIdIsInvalidArgument() throws Exception {
    var stub = stub((u, c) -> new ChannelAccess(SERVER, Set.of()));

    assertThatThrownBy(() -> stub.checkChannelAccess(request("nope", CHANNEL.toString())))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  // Kịch bản: user JWT A hỏi quyền xem kênh của user B
  // Input: Bearer subject A, CheckChannelAccess với user_id B
  // Output: PERMISSION_DENIED và checker không bị gọi
  @Test
  void userAskingAboutAnotherUserIsDenied() throws Exception {
    var stub =
        userStub(
            (u, c) -> {
              throw new AssertionError("checker must not be called");
            },
            USER);

    assertThatThrownBy(() -> stub.checkChannelAccess(request(OTHER.toString(), CHANNEL.toString())))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  // Kịch bản: user JWT A hỏi về chính A
  // Input: Bearer subject A, CheckChannelAccess với user_id A, checker cho VIEW_CHANNEL
  // Output: can_view true
  @Test
  void userAskingAboutSelfWorks() throws Exception {
    var stub =
        userStub((u, c) -> new ChannelAccess(SERVER, Set.of(ChannelPermission.VIEW_CHANNEL)), USER);

    assertThat(stub.checkChannelAccess(request()).getCanView()).isTrue();
  }

  // Kịch bản: service caller hỏi về user bất kỳ
  // Input: service token realtime, user_id khác, checker cho VIEW_CHANNEL
  // Output: can_view true
  @Test
  void serviceCallerMayAskAboutAnyUser() throws Exception {
    var stub = stub((u, c) -> new ChannelAccess(SERVER, Set.of(ChannelPermission.VIEW_CHANNEL)));

    assertThat(stub.checkChannelAccess(request(OTHER.toString(), CHANNEL.toString())).getCanView())
        .isTrue();
  }

  // Kho tham chiếu kênh giả coi mọi kênh đã xóa
  private static final class DeletedRefs implements ChannelRefRepository {
    // Không dùng trong test này
    @Override
    public void upsertServerChannel(ChannelId channelId, UUID serverId) {}

    // Không dùng trong test này
    @Override
    public void upsertDmChannel(ChannelId channelId) {}

    // Không dùng trong test này
    @Override
    public void tombstoneChannel(ChannelId channelId, UUID serverId, Instant at) {}

    // Không dùng trong test này
    @Override
    public void tombstoneServer(UUID serverId, Instant at) {}

    // Luôn báo đã xóa
    @Override
    public boolean isDeleted(ChannelId channelId) {
      return true;
    }

    // Không dùng trong test này
    @Override
    public boolean isDm(ChannelId channelId) {
      return false;
    }
  }
}
