package com.nexus.messaging.infrastructure.grpc;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.proto.community.v1.CheckChannelPermissionsRequest;
import com.nexus.proto.community.v1.CheckChannelPermissionsResponse;
import com.nexus.proto.community.v1.Permission;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Hỏi quyền kênh qua gRPC tới Community, có deadline mỗi call và circuit breaker
public class GrpcChannelPermissionChecker implements ChannelPermissionChecker {

  private static final Logger LOG = LoggerFactory.getLogger(GrpcChannelPermissionChecker.class);

  private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;
  private final CircuitBreaker breaker;
  private final Duration deadline;

  // Nhận kênh đã cấu hình retry, breaker "community" và deadline mỗi call
  public GrpcChannelPermissionChecker(Channel channel, CircuitBreaker breaker, Duration deadline) {
    this.stub = PermissionServiceGrpc.newBlockingStub(channel);
    this.breaker = breaker;
    this.deadline = deadline;
  }

  // Gọi Community qua breaker và dịch lỗi gRPC sang ngoại lệ của application
  @Override
  public ChannelAccess check(UserId userId, ChannelId channelId) {
    CheckChannelPermissionsRequest request =
        CheckChannelPermissionsRequest.newBuilder()
            .setUserId(userId.value().toString())
            .setChannelId(channelId.value().toString())
            .build();
    try {
      CheckChannelPermissionsResponse response =
          breaker.executeSupplier(
              () ->
                  stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                      .checkChannelPermissions(request));
      return toAccess(response);
    } catch (CallNotPermittedException e) {
      throw new DependencyUnavailableException("community circuit breaker is open", e);
    } catch (StatusRuntimeException e) {
      throw translate(e);
    }
  }

  // Dịch NOT_FOUND thành ChannelNotFoundException, lỗi khác thành DependencyUnavailableException
  private static RuntimeException translate(StatusRuntimeException e) {
    return switch (e.getStatus().getCode()) {
      case NOT_FOUND -> new ChannelNotFoundException("channel not found");
      case PERMISSION_DENIED, UNAUTHENTICATED -> {
        LOG.error("Community rejected messaging-service credentials: {}", e.getStatus());
        yield new DependencyUnavailableException("community rejected credentials", e);
      }
      default -> new DependencyUnavailableException("community call failed", e);
    };
  }

  // Đổi response proto sang ChannelAccess, bỏ qua quyền lạ, server_id hỏng thì coi là lỗi phụ thuộc
  private static ChannelAccess toAccess(CheckChannelPermissionsResponse response) {
    Set<ChannelPermission> granted = EnumSet.noneOf(ChannelPermission.class);
    for (Permission permission : response.getGrantedList()) {
      switch (permission) {
        case PERMISSION_VIEW_CHANNEL -> granted.add(ChannelPermission.VIEW_CHANNEL);
        case PERMISSION_SEND_MESSAGES -> granted.add(ChannelPermission.SEND_MESSAGES);
        case PERMISSION_READ_MESSAGE_HISTORY -> granted.add(ChannelPermission.READ_MESSAGE_HISTORY);
        case PERMISSION_ATTACH_FILES -> granted.add(ChannelPermission.ATTACH_FILES);
        case PERMISSION_ADD_REACTIONS -> granted.add(ChannelPermission.ADD_REACTIONS);
        case PERMISSION_MANAGE_MESSAGES -> granted.add(ChannelPermission.MANAGE_MESSAGES);
        default -> LOG.warn("Ignoring unknown permission {}", permission);
      }
    }
    try {
      return new ChannelAccess(UUID.fromString(response.getServerId()), granted);
    } catch (IllegalArgumentException e) {
      throw new DependencyUnavailableException("community returned invalid server_id", e);
    }
  }
}
