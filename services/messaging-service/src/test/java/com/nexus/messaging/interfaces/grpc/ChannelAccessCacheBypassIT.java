package com.nexus.messaging.interfaces.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.common.grpc.AuthServerInterceptor;
import com.nexus.common.grpc.GrpcAuth;
import com.nexus.common.grpc.ServiceCaller;
import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.proto.messaging.v1.ChannelAccessServiceGrpc;
import com.nexus.proto.messaging.v1.CheckChannelAccessRequest;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// Kiểm tra RPC ChannelAccess trong context thật luôn hỏi checker không cache, bỏ qua perm:*
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class ChannelAccessCacheBypassIT extends MessagingIntegrationTest {

  @Autowired private ChannelAccessGrpcService service;
  @Autowired private StringRedisTemplate redis;

  @MockitoBean(name = "uncachedChannelPermissionChecker")
  private ChannelPermissionChecker uncached;

  // Kịch bản: perm:{channel}:{user} còn entry cũ cho phép xem nhưng quyền vừa bị thu hồi
  // Input: seed "{server}|63|0" vào Redis, checker không cache trả granted rỗng, gọi RPC
  // Output: can_view false (không đọc cache), checker không cache bị hỏi đúng 1 lần
  @Test
  void rpcIgnoresStalePermCacheEntry() throws Exception {
    UUID server = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    UUID channelId = UUID.randomUUID();
    redis.opsForValue().set("perm:" + channelId + ":" + user, server + "|63|0");
    when(uncached.check(any(), any())).thenReturn(new ChannelAccess(server, Set.of()));
    JwtDecoder noJwt =
        token -> {
          throw new IllegalStateException("no jwt expected");
        };
    String name = "bypass-" + UUID.randomUUID();
    Server grpc =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .intercept(
                new AuthServerInterceptor(
                    noJwt,
                    Map.of(
                        "realtime-gateway",
                        new ServiceCaller(
                            "dummy-realtime",
                            List.of(
                                "nexus.messaging.v1.ChannelAccessService/CheckChannelAccess")))))
            .addService(service)
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    Metadata headers = new Metadata();
    headers.put(GrpcAuth.SERVICE_TOKEN, "dummy-realtime");
    try {
      boolean canView =
          ChannelAccessServiceGrpc.newBlockingStub(channel)
              .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
              .checkChannelAccess(
                  CheckChannelAccessRequest.newBuilder()
                      .setUserId(user.toString())
                      .setChannelId(channelId.toString())
                      .build())
              .getCanView();

      assertThat(canView).isFalse();
      verify(uncached).check(any(), any());
    } finally {
      channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
      grpc.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }
  }
}
