package com.nexus.messaging.infrastructure.grpc;

import com.nexus.messaging.application.port.UserDirectory;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.proto.identity.v1.GetUserRequest;
import com.nexus.proto.identity.v1.UserServiceGrpc;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Tra user qua GetUser của Identity với deadline mỗi call; ponytail: chưa breaker, thêm khi đo thấy
public class GrpcUserDirectory implements UserDirectory {

  private static final Logger LOG = LoggerFactory.getLogger(GrpcUserDirectory.class);

  private final UserServiceGrpc.UserServiceBlockingStub stub;
  private final Duration deadline;

  // Nhận kênh tới Identity và deadline mỗi call
  public GrpcUserDirectory(Channel channel, Duration deadline) {
    this.stub = UserServiceGrpc.newBlockingStub(channel);
    this.deadline = deadline;
  }

  // OK thì true, NOT_FOUND thì false, lỗi khác thành DependencyUnavailableException
  @Override
  public boolean exists(UserId id) {
    try {
      stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
          .getUser(GetUserRequest.newBuilder().setUserId(id.value().toString()).build());
      return true;
    } catch (StatusRuntimeException e) {
      Status.Code code = e.getStatus().getCode();
      if (code == Status.Code.NOT_FOUND) {
        return false;
      }
      if (code == Status.Code.PERMISSION_DENIED || code == Status.Code.UNAUTHENTICATED) {
        LOG.error("Identity rejected messaging-service credentials: {}", e.getStatus());
      }
      throw new DependencyUnavailableException("identity call failed", e);
    }
  }
}
