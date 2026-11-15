package com.nexus.messaging.interfaces.grpc;

import com.nexus.common.grpc.GrpcAuth;
import com.nexus.common.grpc.GrpcStatuses;
import com.nexus.messaging.application.social.ListFriendsQuery;
import com.nexus.messaging.application.social.StreamAllDmChannelsQuery;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.proto.messaging.v1.DmChannelRecord;
import com.nexus.proto.messaging.v1.ListFriendIdsRequest;
import com.nexus.proto.messaging.v1.ListFriendIdsResponse;
import com.nexus.proto.messaging.v1.SocialServiceGrpc;
import com.nexus.proto.messaging.v1.StreamAllDmChannelsRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

// Dịch vụ gRPC trả danh sách bạn bè cho service khác, user JWT không được gọi
@Service
public class SocialGrpcService extends SocialServiceGrpc.SocialServiceImplBase {

  private static final String DOMAIN = "nexus.messaging";

  private final ListFriendsQuery listFriends;
  private final StreamAllDmChannelsQuery streamDms;

  // Nhận query liệt kê bạn bè và query duyệt kênh DM qua constructor
  public SocialGrpcService(ListFriendsQuery listFriends, StreamAllDmChannelsQuery streamDms) {
    this.listFriends = listFriends;
    this.streamDms = streamDms;
  }

  // Trả id bạn bè của user, user lạ hoặc chưa có bạn là OK với danh sách rỗng
  @Override
  public void listFriendIds(
      ListFriendIdsRequest request, StreamObserver<ListFriendIdsResponse> observer) {
    if (GrpcAuth.callerService().isEmpty()) {
      observer.onError(
          GrpcStatuses.error(
              Status.Code.PERMISSION_DENIED, DOMAIN, "PERMISSION_DENIED", "service caller only"));
      return;
    }
    UserId user;
    try {
      user = UserId.of(request.getUserId());
    } catch (DomainValidationException e) {
      observer.onError(
          GrpcStatuses.error(
              Status.Code.INVALID_ARGUMENT, DOMAIN, "INVALID_ARGUMENT", "Malformed id"));
      return;
    }
    ListFriendIdsResponse.Builder response = ListFriendIdsResponse.newBuilder();
    listFriends.execute(user).forEach(id -> response.addFriendIds(id.toString()));
    observer.onNext(response.build());
    observer.onCompleted();
  }

  // Stream mọi kênh DM cho service caller, user JWT bị từ chối
  @Override
  public void streamAllDmChannels(
      StreamAllDmChannelsRequest request, StreamObserver<DmChannelRecord> observer) {
    if (GrpcAuth.callerService().isEmpty()) {
      observer.onError(
          GrpcStatuses.error(
              Status.Code.PERMISSION_DENIED, DOMAIN, "PERMISSION_DENIED", "service caller only"));
      return;
    }
    streamDms.execute(
        c ->
            observer.onNext(
                DmChannelRecord.newBuilder()
                    .setChannelId(c.id().toString())
                    .setUserLowId(c.pair().low().value().toString())
                    .setUserHighId(c.pair().high().value().toString())
                    .build()));
    observer.onCompleted();
  }
}
