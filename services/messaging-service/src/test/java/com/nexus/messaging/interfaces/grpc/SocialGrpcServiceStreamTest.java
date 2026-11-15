package com.nexus.messaging.interfaces.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.grpc.GrpcAuth;
import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.domain.social.DmChannelRepository;
import com.nexus.proto.messaging.v1.SocialServiceGrpc;
import com.nexus.proto.messaging.v1.StreamAllDmChannelsRequest;
import com.nexus.proto.messaging.v1.StreamAllDmChannelsResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra SocialService/StreamAllDmChannels trên gRPC và Postgres thật, vượt một trang keyset
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@TestPropertySource(
    properties = {
      "app.grpc.service-callers.realtime-gateway.token=dummy-realtime",
      "app.grpc.service-callers.notification-service.token=dummy-notification"
    })
class SocialGrpcServiceStreamTest extends MessagingIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DmChannelRepository channels;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transaction;
  @Autowired private GrpcServerLifecycle grpcServer;

  private ManagedChannel channel;

  // Đóng kênh gRPC client sau mỗi test
  @AfterEach
  void closeChannel() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  // Kịch bản: notification-service stream toàn bộ kênh DM vượt một trang keyset
  // Input: token notification, 2200 kênh DM thêm hàng loạt bằng SQL
  // Output: đủ số bản ghi như DB, không trùng, tăng dần theo id, id và hai participant khớp
  @Test
  void streamAllDmChannelsReturnsEveryChannel() {
    jdbc.update(
        "insert into messaging.dm_channels (id, user_low_id, user_high_id, created_at)"
            + " select gen_random_uuid(),"
            + " ('00000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid,"
            + " ('ffffffff-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid, now()"
            + " from generate_series(1, 2200) g");

    List<StreamAllDmChannelsResponse> streamed = new ArrayList<>();
    stub("dummy-notification")
        .streamAllDmChannels(StreamAllDmChannelsRequest.getDefaultInstance())
        .forEachRemaining(streamed::add);

    List<String> ids = streamed.stream().map(StreamAllDmChannelsResponse::getChannelId).toList();
    assertThat(ids)
        .hasSize(jdbc.queryForObject("select count(*) from messaging.dm_channels", int.class));
    assertThat(ids).hasSizeGreaterThanOrEqualTo(2200);
    assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
    assertThat(ids).isSorted();
    var expected =
        jdbc.query(
            "select id, user_low_id, user_high_id from messaging.dm_channels",
            (rs, i) -> rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getString(3));
    var actual =
        streamed.stream()
            .map(c -> c.getChannelId() + ":" + c.getUserLowId() + ":" + c.getUserHighId())
            .toList();
    assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
  }

  // Kịch bản: user thường gọi StreamAllDmChannels
  // Input: Bearer của một user, không có service token
  // Output: PERMISSION_DENIED
  @Test
  void streamAllDmChannelsRejectsUserJwt() {
    var stub = stub(GrpcAuth.AUTHORIZATION, bearer(UUID.randomUUID()));

    assertThatThrownBy(
            () -> stub.streamAllDmChannels(StreamAllDmChannelsRequest.getDefaultInstance()).next())
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  // Kịch bản: service không có StreamAllDmChannels trong allowlist
  // Input: token realtime-gateway gọi StreamAllDmChannels
  // Output: PERMISSION_DENIED
  @Test
  void streamAllDmChannelsRejectsServiceNotInAllowlist() {
    var stub = stub("dummy-realtime");

    assertThatThrownBy(
            () -> stub.streamAllDmChannels(StreamAllDmChannelsRequest.getDefaultInstance()).next())
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  // Kịch bản: duyệt một lô kênh DM trong transaction không được giữ entity trong bộ nhớ
  // Input: một kênh DM, pageAll lô 1000 trong một transaction rồi đếm entity được quản lý
  // Output: lô không rỗng và persistence context không còn entity nào
  @Test
  void pageAllDoesNotRetainEntitiesInPersistenceContext() {
    jdbc.update(
        "insert into messaging.dm_channels (id, user_low_id, user_high_id, created_at)"
            + " values (gen_random_uuid(), '00000000-0000-0000-0000-00000000abcd',"
            + " 'ffffffff-ffff-ffff-ffff-00000000abcd', now()) on conflict do nothing");

    int managed =
        transaction.execute(
            status -> {
              assertThat(channels.pageAll(null, 1000)).isNotEmpty();
              return entityManager.unwrap(Session.class).getStatistics().getEntityCount();
            });

    assertThat(managed).isZero();
  }

  // Stub blocking gọi bằng service token cho trước
  private SocialServiceGrpc.SocialServiceBlockingStub stub(String serviceToken) {
    return stub(GrpcAuth.SERVICE_TOKEN, serviceToken);
  }

  // Stub blocking tới cổng gRPC thật, mang sẵn một header cho trước
  private SocialServiceGrpc.SocialServiceBlockingStub stub(Metadata.Key<String> key, String value) {
    Metadata headers = new Metadata();
    headers.put(key, value);
    channel =
        ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
    return SocialServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }
}
