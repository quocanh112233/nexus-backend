package com.nexus.messaging.interfaces.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.common.grpc.AuthServerInterceptor;
import com.nexus.common.grpc.GrpcAuth;
import com.nexus.common.grpc.ServiceCaller;
import com.nexus.messaging.application.social.ListFriendsQuery;
import com.nexus.messaging.application.social.StreamAllDmChannelsQuery;
import com.nexus.proto.messaging.v1.ChannelAccessServiceGrpc;
import com.nexus.proto.messaging.v1.CheckChannelAccessRequest;
import com.nexus.proto.messaging.v1.ListFriendIdsRequest;
import com.nexus.proto.messaging.v1.SocialServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

// Kiểm tra SocialGrpcService qua gRPC in-process với AuthServerInterceptor thật và query giả
class SocialGrpcServiceTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID F1 = UUID.randomUUID();
  private static final UUID F2 = UUID.randomUUID();
  private static final String PRESENCE_TOKEN = "dummy-presence";
  private static final String REALTIME_TOKEN = "dummy-realtime";

  private final ListFriendsQuery query = mock(ListFriendsQuery.class);
  private Server server;
  private ManagedChannel channel;

  // Tắt kênh và server sau mỗi test
  @AfterEach
  void tearDown() throws Exception {
    channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
  }

  // Dựng server in-process có hai service và AuthServerInterceptor thật, trả kênh gắn headers
  private ManagedChannel connect(Metadata headers) throws Exception {
    JwtDecoder decoder =
        token -> Jwt.withTokenValue(token).header("alg", "none").subject(token).build();
    Map<String, ServiceCaller> callers =
        Map.of(
            "presence-service",
            new ServiceCaller(
                PRESENCE_TOKEN, List.of("nexus.messaging.v1.SocialService/ListFriendIds")),
            "realtime-gateway",
            new ServiceCaller(
                REALTIME_TOKEN,
                List.of("nexus.messaging.v1.ChannelAccessService/CheckChannelAccess")));
    String name = "social-" + UUID.randomUUID();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .intercept(new AuthServerInterceptor(decoder, callers))
            .addService(new SocialGrpcService(query, mock(StreamAllDmChannelsQuery.class)))
            .addService(new ChannelAccessGrpcService((u, c) -> null))
            .build()
            .start();
    channel =
        InProcessChannelBuilder.forName(name)
            .directExecutor()
            .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
            .build();
    return channel;
  }

  // Stub Social gọi bằng service token cho trước
  private SocialServiceGrpc.SocialServiceBlockingStub social(String token) throws Exception {
    Metadata headers = new Metadata();
    headers.put(GrpcAuth.SERVICE_TOKEN, token);
    return SocialServiceGrpc.newBlockingStub(connect(headers));
  }

  // Dựng request ListFriendIds
  private static ListFriendIdsRequest request(String userId) {
    return ListFriendIdsRequest.newBuilder().setUserId(userId).build();
  }

  // Khẳng định lệnh gọi lỗi với status code mong đợi
  private static void assertCode(Runnable call, Status.Code code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode()).isEqualTo(code));
  }

  // Kịch bản: presence-service đúng token hỏi bạn bè của user
  // Input: service token presence, query trả F1 và F2 (quan hệ hai chiều)
  // Output: friend_ids chứa đủ F1 và F2
  @Test
  void presenceServiceGetsAllFriendIds() throws Exception {
    when(query.execute(any())).thenReturn(List.of(F1, F2));

    var response = social(PRESENCE_TOKEN).listFriendIds(request(USER.toString()));

    assertThat(response.getFriendIdsList()).containsExactlyInAnyOrder(F1.toString(), F2.toString());
  }

  // Kịch bản: user không có bạn hoặc không tồn tại
  // Input: service token presence, query trả danh sách rỗng
  // Output: OK với friend_ids rỗng
  @Test
  void noFriendsReturnsEmptyList() throws Exception {
    when(query.execute(any())).thenReturn(List.of());

    var response = social(PRESENCE_TOKEN).listFriendIds(request(USER.toString()));

    assertThat(response.getFriendIdsList()).isEmpty();
  }

  // Kịch bản: service token sai
  // Input: x-service-token không khớp caller nào
  // Output: UNAUTHENTICATED và query không bị gọi
  @Test
  void wrongTokenIsUnauthenticated() throws Exception {
    var stub = social("wrong-token");

    assertCode(() -> stub.listFriendIds(request(USER.toString())), Status.Code.UNAUTHENTICATED);
    verifyNoInteractions(query);
  }

  // Kịch bản: không gửi credential nào
  // Input: metadata rỗng
  // Output: UNAUTHENTICATED và query không bị gọi
  @Test
  void noCredentialsIsUnauthenticated() throws Exception {
    var stub = SocialServiceGrpc.newBlockingStub(connect(new Metadata()));

    assertCode(() -> stub.listFriendIds(request(USER.toString())), Status.Code.UNAUTHENTICATED);
    verifyNoInteractions(query);
  }

  // Kịch bản: user JWT hỏi về chính mình vẫn bị chặn vì RPC chỉ dành cho service
  // Input: Bearer subject USER, user_id USER
  // Output: PERMISSION_DENIED và query không bị gọi
  @Test
  void jwtUserIsPermissionDenied() throws Exception {
    Metadata headers = new Metadata();
    headers.put(GrpcAuth.AUTHORIZATION, "Bearer " + USER);
    var stub = SocialServiceGrpc.newBlockingStub(connect(headers));

    assertCode(() -> stub.listFriendIds(request(USER.toString())), Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(query);
  }

  // Kịch bản: token presence gọi RPC khác của messaging ngoài allowlist
  // Input: service token presence, CheckChannelAccess
  // Output: PERMISSION_DENIED
  @Test
  void presenceTokenCannotCallOtherRpc() throws Exception {
    Metadata headers = new Metadata();
    headers.put(GrpcAuth.SERVICE_TOKEN, PRESENCE_TOKEN);
    var stub = ChannelAccessServiceGrpc.newBlockingStub(connect(headers));
    var req =
        CheckChannelAccessRequest.newBuilder()
            .setUserId(USER.toString())
            .setChannelId(UUID.randomUUID().toString())
            .build();

    assertCode(() -> stub.checkChannelAccess(req), Status.Code.PERMISSION_DENIED);
  }

  // Kịch bản: token realtime-gateway gọi ListFriendIds ngoài allowlist
  // Input: service token realtime, ListFriendIds
  // Output: PERMISSION_DENIED và query không bị gọi
  @Test
  void realtimeTokenCannotCallListFriendIds() throws Exception {
    var stub = social(REALTIME_TOKEN);

    assertCode(() -> stub.listFriendIds(request(USER.toString())), Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(query);
  }

  // Kịch bản: user_id sai định dạng
  // Input: service token presence, user_id không phải UUID
  // Output: INVALID_ARGUMENT và query không bị gọi
  @Test
  void malformedUserIdIsInvalidArgument() throws Exception {
    var stub = social(PRESENCE_TOKEN);

    assertCode(() -> stub.listFriendIds(request("nope")), Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(query);
  }
}
