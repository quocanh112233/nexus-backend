package com.nexus.messaging.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nexus.common.grpc.GrpcAuth;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.proto.community.v1.CheckChannelPermissionsRequest;
import com.nexus.proto.community.v1.CheckChannelPermissionsResponse;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

// Kiểm tra app.grpc.service-token được nối vào kênh Community qua cấu hình Spring thật
class CommunityChannelServiceTokenTest {

  // Kịch bản: bean communityChannel đọc app.grpc.service-token và gắn vào call không có JWT
  // Input: context với app.grpc.service-token=dummy-messaging, server Netty ghi lại metadata
  // Output: server thấy x-service-token dummy-messaging và không có authorization
  @Test
  void communityChannelBeanAttachesConfiguredServiceToken() throws Exception {
    AtomicReference<Metadata> seen = new AtomicReference<>();
    ServerInterceptor capture =
        new ServerInterceptor() {
          // Ghi lại metadata rồi chuyển tiếp call
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            seen.set(headers);
            return next.startCall(call, headers);
          }
        };
    Server server =
        NettyServerBuilder.forPort(0)
            .addService(
                ServerInterceptors.intercept(
                    new PermissionServiceGrpc.PermissionServiceImplBase() {
                      // Trả kết quả rỗng
                      @Override
                      public void checkChannelPermissions(
                          CheckChannelPermissionsRequest request,
                          StreamObserver<CheckChannelPermissionsResponse> observer) {
                        observer.onNext(CheckChannelPermissionsResponse.getDefaultInstance());
                        observer.onCompleted();
                      }
                    },
                    capture))
            .build()
            .start();
    try {
      new ApplicationContextRunner()
          .withInitializer(
              ctx ->
                  ctx.getBeanFactory()
                      .setConversionService(ApplicationConversionService.getSharedInstance()))
          .withUserConfiguration(CommunityChannelConfig.class)
          .withBean(ObservationRegistry.class, () -> ObservationRegistry.NOOP)
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withBean(ChannelRefRepository.class, () -> mock(ChannelRefRepository.class))
          .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
          .withPropertyValues(
              "app.grpc.community.target=dns:///localhost:" + server.getPort(),
              "app.grpc.service-token=dummy-messaging")
          .run(
              context -> {
                PermissionServiceGrpc.newBlockingStub(context.getBean(ManagedChannel.class))
                    .checkChannelPermissions(CheckChannelPermissionsRequest.getDefaultInstance());
                assertThat(seen.get().get(GrpcAuth.SERVICE_TOKEN)).isEqualTo("dummy-messaging");
                assertThat(seen.get().containsKey(GrpcAuth.AUTHORIZATION)).isFalse();
              });
    } finally {
      server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }
  }
}
