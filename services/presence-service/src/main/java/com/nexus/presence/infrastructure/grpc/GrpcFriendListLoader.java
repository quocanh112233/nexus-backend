package com.nexus.presence.infrastructure.grpc;

import com.nexus.presence.application.relation.FriendListLoader;
import com.nexus.presence.application.relation.RelationUnavailableException;
import com.nexus.proto.messaging.v1.ListFriendIdsRequest;
import com.nexus.proto.messaging.v1.SocialServiceGrpc;
import io.grpc.Channel;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

// Nạp danh sách bạn của user qua gRPC Messaging (deadline 500 ms), mọi lỗi thành không-khả-dụng
public class GrpcFriendListLoader implements FriendListLoader {

  private final SocialServiceGrpc.SocialServiceBlockingStub stub;

  // Nhận kênh tới Messaging
  public GrpcFriendListLoader(Channel messaging) {
    this.stub = SocialServiceGrpc.newBlockingStub(messaging);
  }

  // Gọi ListFriendIds và đổi kết quả sang UUID
  @Override
  public Set<UUID> friendIdsOf(UUID userId) {
    try {
      return stub
          .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
          .listFriendIds(ListFriendIdsRequest.newBuilder().setUserId(userId.toString()).build())
          .getFriendIdsList()
          .stream()
          .map(UUID::fromString)
          .collect(Collectors.toSet());
    } catch (RuntimeException e) {
      throw new RelationUnavailableException("messaging call failed", e);
    }
  }
}
