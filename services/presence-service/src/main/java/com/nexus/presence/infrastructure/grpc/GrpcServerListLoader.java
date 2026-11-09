package com.nexus.presence.infrastructure.grpc;

import com.nexus.presence.application.relation.RelationUnavailableException;
import com.nexus.presence.application.relation.ServerListLoader;
import com.nexus.proto.community.v1.ListServerIdsForUserRequest;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import io.grpc.Channel;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

// Nạp danh sách server của user qua gRPC Community (deadline 500 ms), mọi lỗi thành không-khả-dụng
public class GrpcServerListLoader implements ServerListLoader {

  private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;

  // Nhận kênh tới Community
  public GrpcServerListLoader(Channel community) {
    this.stub = PermissionServiceGrpc.newBlockingStub(community);
  }

  // Gọi ListServerIdsForUser và đổi kết quả sang UUID
  @Override
  public Set<UUID> serverIdsOf(UUID userId) {
    try {
      return stub
          .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
          .listServerIdsForUser(
              ListServerIdsForUserRequest.newBuilder().setUserId(userId.toString()).build())
          .getServerIdsList()
          .stream()
          .map(UUID::fromString)
          .collect(Collectors.toSet());
    } catch (RuntimeException e) {
      throw new RelationUnavailableException("community call failed", e);
    }
  }
}
