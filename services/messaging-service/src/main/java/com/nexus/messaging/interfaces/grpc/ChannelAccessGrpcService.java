package com.nexus.messaging.interfaces.grpc;

import com.nexus.common.grpc.GrpcAuth;
import com.nexus.common.grpc.GrpcStatuses;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.proto.messaging.v1.ChannelAccessServiceGrpc;
import com.nexus.proto.messaging.v1.CheckChannelAccessRequest;
import com.nexus.proto.messaging.v1.CheckChannelAccessResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

// Dịch vụ gRPC cho gateway hỏi quyền xem kênh, user chỉ hỏi về mình, kênh lạ thì can_view=false
@Service
public class ChannelAccessGrpcService
    extends ChannelAccessServiceGrpc.ChannelAccessServiceImplBase {

  private static final String DOMAIN = "nexus.messaging";

  private final ChannelPermissionChecker checker;

  // Nhận checker quyền kênh không cache (tombstone bọc gRPC) để re-check thu hồi luôn tươi
  public ChannelAccessGrpcService(
      @Qualifier("uncachedChannelPermissionChecker") ChannelPermissionChecker checker) {
    this.checker = checker;
  }

  // Trả can_view và server_id, kênh không tồn tại hoặc đã xóa là OK với can_view=false
  @Override
  public void checkChannelAccess(
      CheckChannelAccessRequest request, StreamObserver<CheckChannelAccessResponse> observer) {
    CheckChannelAccessResponse.Builder response = CheckChannelAccessResponse.newBuilder();
    try {
      UserId user = UserId.of(request.getUserId());
      if (GrpcAuth.callerService().isEmpty()
          && GrpcAuth.userId().filter(user.value()::equals).isEmpty()) {
        throw GrpcStatuses.error(
            Status.Code.PERMISSION_DENIED, DOMAIN, "PERMISSION_DENIED", "user_id mismatch");
      }
      ChannelAccess access = checker.check(user, ChannelId.of(request.getChannelId()));
      if (access.has(ChannelPermission.VIEW_CHANNEL)) {
        response.setCanView(true).setServerId(access.serverId().toString());
      }
    } catch (ChannelNotFoundException e) {
      response.setCanView(false);
    } catch (StatusRuntimeException e) {
      observer.onError(e);
      return;
    } catch (DomainValidationException e) {
      observer.onError(
          GrpcStatuses.error(
              Status.Code.INVALID_ARGUMENT, DOMAIN, "INVALID_ARGUMENT", "Malformed id"));
      return;
    } catch (DependencyUnavailableException e) {
      observer.onError(
          GrpcStatuses.error(Status.Code.UNAVAILABLE, DOMAIN, "UNAVAILABLE", "Community down"));
      return;
    }
    observer.onNext(response.build());
    observer.onCompleted();
  }
}
