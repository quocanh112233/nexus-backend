package com.nexus.messaging.infrastructure.grpc;

import com.nexus.common.grpc.ClientMetadataInterceptor;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.social.DmChannelRepository;
import com.nexus.messaging.domain.social.FriendshipRepository;
import com.nexus.messaging.infrastructure.cache.CachingChannelPermissionChecker;
import com.nexus.messaging.infrastructure.persistence.channel.DmAwareChannelPermissionChecker;
import com.nexus.messaging.infrastructure.persistence.channel.TombstoneChannelPermissionChecker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

// Dựng kênh gRPC, circuit breaker và checker quyền có cache tới Community
@Configuration
public class CommunityChannelConfig {

  private static final Set<Status.Code> BREAKER_CODES =
      Set.of(
          Status.Code.UNAVAILABLE,
          Status.Code.DEADLINE_EXCEEDED,
          Status.Code.INTERNAL,
          Status.Code.UNKNOWN);

  // Áp retryPolicy (3 lần thử UNAVAILABLE), interceptor metadata và observation lên builder
  public static ManagedChannelBuilder<?> configure(
      ManagedChannelBuilder<?> builder, ObservationRegistry registry) {
    return configure(builder, registry, null);
  }

  // Như trên, thêm service token để call không có JWT user vẫn xác thực được với Community
  public static ManagedChannelBuilder<?> configure(
      ManagedChannelBuilder<?> builder, ObservationRegistry registry, String serviceToken) {
    Map<String, Object> retryPolicy =
        Map.of(
            "maxAttempts",
            3.0,
            "initialBackoff",
            "0.05s",
            "maxBackoff",
            "0.1s",
            "backoffMultiplier",
            2.0,
            "retryableStatusCodes",
            List.of("UNAVAILABLE"));
    Map<String, Object> methodConfig =
        Map.of(
            "name",
            List.of(Map.of("service", "nexus.community.v1.PermissionService")),
            "retryPolicy",
            retryPolicy);
    builder.defaultServiceConfig(Map.of("methodConfig", List.of(methodConfig)));
    builder.enableRetry();
    builder.intercept(new ClientMetadataInterceptor("messaging-service", serviceToken));
    builder.intercept(new ObservationGrpcClientInterceptor(registry));
    return builder;
  }

  // Cấu hình breaker chỉ ghi nhận lỗi hạ tầng, NOT_FOUND và PERMISSION_DENIED không tính
  public static CircuitBreakerConfig breakerConfig(
      int window, float failureRate, Duration waitInOpen, int halfOpenCalls) {
    return CircuitBreakerConfig.custom()
        .slidingWindowSize(window)
        .minimumNumberOfCalls(window)
        .failureRateThreshold(failureRate)
        .waitDurationInOpenState(waitInOpen)
        .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
        .recordException(
            e ->
                e instanceof StatusRuntimeException sre
                    && BREAKER_CODES.contains(sre.getStatus().getCode()))
        .build();
  }

  // Xuất trạng thái breaker thành gauge resilience4j.circuitbreaker.state
  public static void bindState(CircuitBreaker breaker, MeterRegistry registry) {
    for (CircuitBreaker.State state : CircuitBreaker.State.values()) {
      Gauge.builder(
              "resilience4j.circuitbreaker.state", () -> breaker.getState() == state ? 1.0 : 0.0)
          .tag("name", breaker.getName())
          .tag("state", state.name().toLowerCase())
          .register(registry);
    }
  }

  // Dựng kênh tới target không dùng service token
  public static ManagedChannel newChannel(String target, ObservationRegistry registry) {
    return newChannel(target, registry, null);
  }

  // Dựng kênh plaintext (mạng Docker nội bộ, TLS ở BL-13) tới target (round_robin), kết nối ngay
  public static ManagedChannel newChannel(
      String target, ObservationRegistry registry, String serviceToken) {
    ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(target);
    builder.usePlaintext();
    builder.defaultLoadBalancingPolicy("round_robin");
    ManagedChannel channel = configure(builder, registry, serviceToken).build();
    channel.getState(true);
    return channel;
  }

  // Kênh tới Community, đóng khi context tắt
  @Bean(destroyMethod = "shutdownNow")
  ManagedChannel communityChannel(
      @Value("${app.grpc.community.target}") String target,
      @Value("${app.grpc.service-token:}") String serviceToken,
      ObservationRegistry registry) {
    return newChannel(target, registry, serviceToken);
  }

  // Breaker "community" theo app.grpc.community.circuit-breaker.*, đã đăng ký metric
  @Bean
  CircuitBreaker communityCircuitBreaker(
      MeterRegistry registry,
      @Value("${app.grpc.community.circuit-breaker.sliding-window-size:20}") int window,
      @Value("${app.grpc.community.circuit-breaker.failure-rate-threshold:50}") float rate,
      @Value("${app.grpc.community.circuit-breaker.wait-duration-in-open-state:10s}")
          Duration waitInOpen,
      @Value("${app.grpc.community.circuit-breaker.permitted-calls-in-half-open-state:3}")
          int halfOpenCalls) {
    CircuitBreaker breaker =
        CircuitBreaker.of("community", breakerConfig(window, rate, waitInOpen, halfOpenCalls));
    bindState(breaker, registry);
    return breaker;
  }

  // Checker kênh server không cache: tombstone bọc gRPC tới Community
  @Bean
  TombstoneChannelPermissionChecker serverChannelPermissionChecker(
      @Qualifier("communityChannel") ManagedChannel communityChannel,
      CircuitBreaker communityCircuitBreaker,
      ChannelRefRepository channelRefs,
      @Value("${app.grpc.community.deadline:300ms}") Duration deadline) {
    return new TombstoneChannelPermissionChecker(
        channelRefs,
        new GrpcChannelPermissionChecker(communityChannel, communityCircuitBreaker, deadline));
  }

  // Checker không cache cho ChannelAccess RPC: DM tính tại chỗ, kênh server luôn hỏi Community
  @Bean
  ChannelPermissionChecker uncachedChannelPermissionChecker(
      TombstoneChannelPermissionChecker serverChecker,
      ChannelRefRepository channelRefs,
      DmChannelRepository dms,
      FriendshipRepository friendships) {
    return new DmAwareChannelPermissionChecker(channelRefs, dms, friendships, serverChecker);
  }

  // Checker mặc định cho REST và message: DM tính trước, kênh server qua cache Redis nếu bật cờ
  @Bean
  @Primary
  ChannelPermissionChecker channelPermissionChecker(
      TombstoneChannelPermissionChecker serverChecker,
      ChannelRefRepository channelRefs,
      DmChannelRepository dms,
      FriendshipRepository friendships,
      StringRedisTemplate redis,
      MeterRegistry registry,
      @Value("${app.cache.permission-ttl:60s}") Duration ttl,
      @Value("${app.cache.permission-enabled:true}") boolean cacheEnabled) {
    ChannelPermissionChecker server =
        cacheEnabled
            ? new CachingChannelPermissionChecker(serverChecker, redis, registry, ttl)
            : serverChecker;
    return new DmAwareChannelPermissionChecker(channelRefs, dms, friendships, server);
  }
}
