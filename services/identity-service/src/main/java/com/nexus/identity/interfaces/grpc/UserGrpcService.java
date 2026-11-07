package com.nexus.identity.interfaces.grpc;

import com.nexus.common.grpc.GrpcStatuses;
import com.nexus.identity.application.user.GetProfileQuery;
import com.nexus.identity.application.user.PublicProfile;
import com.nexus.identity.application.user.UserNotFoundException;
import com.nexus.proto.identity.v1.BatchGetUsersRequest;
import com.nexus.proto.identity.v1.BatchGetUsersResponse;
import com.nexus.proto.identity.v1.GetUserRequest;
import com.nexus.proto.identity.v1.GetUserResponse;
import com.nexus.proto.identity.v1.User;
import com.nexus.proto.identity.v1.UserServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Dịch vụ gRPC tra cứu thông tin công khai của user cho các service khác
@Service
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {

  private static final String DOMAIN = "nexus.identity";
  private static final int MAX_BATCH = 100;

  private final GetProfileQuery profiles;

  // Nhận truy vấn hồ sơ của tầng application qua constructor
  public UserGrpcService(GetProfileQuery profiles) {
    this.profiles = profiles;
  }

  // Trả thông tin công khai của một user, không có thì NOT_FOUND
  @Override
  public void getUser(GetUserRequest request, StreamObserver<GetUserResponse> observer) {
    try {
      PublicProfile profile = profiles.byId(parse(request.getUserId()));
      observer.onNext(GetUserResponse.newBuilder().setUser(toProto(profile)).build());
      observer.onCompleted();
    } catch (UserNotFoundException e) {
      observer.onError(
          GrpcStatuses.error(Status.Code.NOT_FOUND, DOMAIN, "NOT_FOUND", "User not found"));
    } catch (StatusRuntimeException e) {
      observer.onError(e);
    }
  }

  // Trả các user tồn tại trong tối đa 100 id, id lạ bị bỏ qua
  @Override
  public void batchGetUsers(
      BatchGetUsersRequest request, StreamObserver<BatchGetUsersResponse> observer) {
    try {
      if (request.getUserIdsCount() > MAX_BATCH) {
        throw invalid("Too many ids");
      }
      List<UUID> ids = request.getUserIdsList().stream().map(UserGrpcService::parse).toList();
      observer.onNext(
          BatchGetUsersResponse.newBuilder()
              .addAllUsers(profiles.byIds(ids).stream().map(UserGrpcService::toProto).toList())
              .build());
      observer.onCompleted();
    } catch (StatusRuntimeException e) {
      observer.onError(e);
    }
  }

  // Ánh xạ hồ sơ công khai sang proto User, avatar null thành chuỗi rỗng
  private static User toProto(PublicProfile p) {
    return User.newBuilder()
        .setUserId(p.id().toString())
        .setUsername(p.username())
        .setDisplayName(p.displayName())
        .setAvatarUrl(p.avatarUrl() == null ? "" : p.avatarUrl())
        .build();
  }

  // Phân tích UUID, sai định dạng thì INVALID_ARGUMENT
  private static UUID parse(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw invalid("Malformed id");
    }
  }

  // Lỗi INVALID_ARGUMENT kèm lý do
  private static StatusRuntimeException invalid(String message) {
    return GrpcStatuses.error(Status.Code.INVALID_ARGUMENT, DOMAIN, "INVALID_ARGUMENT", message);
  }
}
