package com.nexus.community.interfaces.grpc;

import com.nexus.common.grpc.GrpcAuth;
import com.nexus.common.grpc.GrpcStatuses;
import com.nexus.community.application.channel.ChannelNotFoundException;
import com.nexus.community.application.server.ChannelPermissionResult;
import com.nexus.community.application.server.CheckChannelPermissionsQuery;
import com.nexus.community.application.server.CheckServerMembershipQuery;
import com.nexus.community.application.server.ListMyServersQuery;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import com.nexus.proto.community.v1.CheckChannelPermissionsRequest;
import com.nexus.proto.community.v1.CheckChannelPermissionsResponse;
import com.nexus.proto.community.v1.CheckServerMembershipRequest;
import com.nexus.proto.community.v1.CheckServerMembershipResponse;
import com.nexus.proto.community.v1.ListServerIdsForUserRequest;
import com.nexus.proto.community.v1.ListServerIdsForUserResponse;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

// Dịch vụ gRPC trả quyền kênh và thành viên, user chỉ hỏi về mình, service hỏi mọi user
@Service
public class PermissionGrpcService extends PermissionServiceGrpc.PermissionServiceImplBase {

  private static final String DOMAIN = "nexus.community";

  // Bảng ánh xạ quyền domain sang enum proto, thứ tự theo số enum proto
  private static final Map<Permission, com.nexus.proto.community.v1.Permission> PROTO_PERMISSIONS =
      new java.util.LinkedHashMap<>();

  static {
    PROTO_PERMISSIONS.put(
        Permission.VIEW_CHANNEL, com.nexus.proto.community.v1.Permission.PERMISSION_VIEW_CHANNEL);
    PROTO_PERMISSIONS.put(
        Permission.SEND_MESSAGES, com.nexus.proto.community.v1.Permission.PERMISSION_SEND_MESSAGES);
    PROTO_PERMISSIONS.put(
        Permission.READ_MESSAGE_HISTORY,
        com.nexus.proto.community.v1.Permission.PERMISSION_READ_MESSAGE_HISTORY);
    PROTO_PERMISSIONS.put(
        Permission.ATTACH_FILES, com.nexus.proto.community.v1.Permission.PERMISSION_ATTACH_FILES);
    PROTO_PERMISSIONS.put(
        Permission.ADD_REACTIONS, com.nexus.proto.community.v1.Permission.PERMISSION_ADD_REACTIONS);
    PROTO_PERMISSIONS.put(
        Permission.MANAGE_MESSAGES,
        com.nexus.proto.community.v1.Permission.PERMISSION_MANAGE_MESSAGES);
  }

  private final CheckChannelPermissionsQuery channelQuery;
  private final CheckServerMembershipQuery membershipQuery;
  private final ListMyServersQuery listServersQuery;

  // Nhận ba truy vấn của tầng application qua constructor
  public PermissionGrpcService(
      CheckChannelPermissionsQuery channelQuery,
      CheckServerMembershipQuery membershipQuery,
      ListMyServersQuery listServersQuery) {
    this.channelQuery = channelQuery;
    this.membershipQuery = membershipQuery;
    this.listServersQuery = listServersQuery;
  }

  // Trả server chứa kênh và các quyền proto user có trên kênh
  @Override
  public void checkChannelPermissions(
      CheckChannelPermissionsRequest request,
      StreamObserver<CheckChannelPermissionsResponse> observer) {
    try {
      UserId user = authorizedUser(request.getUserId());
      ChannelPermissionResult result =
          channelQuery.execute(user, ChannelId.of(request.getChannelId()));
      List<com.nexus.proto.community.v1.Permission> granted =
          PROTO_PERMISSIONS.entrySet().stream()
              .filter(e -> result.permissions().has(e.getKey()))
              .map(Map.Entry::getValue)
              .toList();
      observer.onNext(
          CheckChannelPermissionsResponse.newBuilder()
              .setServerId(result.serverId().value().toString())
              .addAllGranted(granted)
              .build());
      observer.onCompleted();
    } catch (DomainValidationException e) {
      observer.onError(invalid());
    } catch (ChannelNotFoundException e) {
      observer.onError(
          GrpcStatuses.error(Status.Code.NOT_FOUND, DOMAIN, "NOT_FOUND", "Channel not found"));
    } catch (io.grpc.StatusRuntimeException e) {
      observer.onError(e);
    }
  }

  // Trả is_member của user trong server
  @Override
  public void checkServerMembership(
      CheckServerMembershipRequest request,
      StreamObserver<CheckServerMembershipResponse> observer) {
    try {
      UserId user = authorizedUser(request.getUserId());
      boolean member = membershipQuery.execute(user, ServerId.of(request.getServerId()));
      observer.onNext(CheckServerMembershipResponse.newBuilder().setIsMember(member).build());
      observer.onCompleted();
    } catch (DomainValidationException e) {
      observer.onError(invalid());
    } catch (io.grpc.StatusRuntimeException e) {
      observer.onError(e);
    }
  }

  // Trả id mọi server mà user là thành viên
  @Override
  public void listServerIdsForUser(
      ListServerIdsForUserRequest request, StreamObserver<ListServerIdsForUserResponse> observer) {
    try {
      UserId user = authorizedUser(request.getUserId());
      observer.onNext(
          ListServerIdsForUserResponse.newBuilder()
              .addAllServerIds(
                  listServersQuery.execute(user).stream().map(s -> s.id().toString()).toList())
              .build());
      observer.onCompleted();
    } catch (DomainValidationException e) {
      observer.onError(invalid());
    } catch (io.grpc.StatusRuntimeException e) {
      observer.onError(e);
    }
  }

  // Phân tích user_id, service caller đã xác thực được hỏi về mọi user, user chỉ về chính mình
  private static UserId authorizedUser(String userId) {
    UserId user = UserId.of(userId);
    if (GrpcAuth.callerService().isEmpty()
        && !GrpcAuth.userId().filter(user.value()::equals).isPresent()) {
      throw GrpcStatuses.error(
          Status.Code.PERMISSION_DENIED, DOMAIN, "PERMISSION_DENIED", "user_id mismatch");
    }
    return user;
  }

  // Lỗi INVALID_ARGUMENT cho id không phải UUID
  private static io.grpc.StatusRuntimeException invalid() {
    return GrpcStatuses.error(
        Status.Code.INVALID_ARGUMENT, DOMAIN, "INVALID_ARGUMENT", "Malformed id");
  }
}
