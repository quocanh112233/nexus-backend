package com.nexus.realtime.infrastructure.grpc;

import com.nexus.proto.community.v1.CheckServerMembershipRequest;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import com.nexus.proto.messaging.v1.ChannelAccessServiceGrpc;
import com.nexus.proto.messaging.v1.CheckChannelAccessRequest;
import com.nexus.proto.messaging.v1.CheckChannelAccessResponse;
import com.nexus.realtime.application.subscription.AccessCheckUnavailableException;
import com.nexus.realtime.application.subscription.AccessChecker;
import io.grpc.Channel;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Hỏi quyền qua gRPC tới Messaging (500 ms) và Community (300 ms), mọi lỗi thành không-khả-dụng
public class GrpcAccessChecker implements AccessChecker {

  private final ChannelAccessServiceGrpc.ChannelAccessServiceBlockingStub channels;
  private final PermissionServiceGrpc.PermissionServiceBlockingStub members;

  // Nhận kênh tới Messaging và Community
  public GrpcAccessChecker(Channel messaging, Channel community) {
    this.channels = ChannelAccessServiceGrpc.newBlockingStub(messaging);
    this.members = PermissionServiceGrpc.newBlockingStub(community);
  }

  // Hỏi Messaging user có xem được kênh không
  @Override
  public Optional<UUID> canViewChannel(UUID userId, UUID channelId) {
    try {
      CheckChannelAccessResponse response =
          channels
              .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
              .checkChannelAccess(
                  CheckChannelAccessRequest.newBuilder()
                      .setUserId(userId.toString())
                      .setChannelId(channelId.toString())
                      .build());
      return response.getCanView()
          ? Optional.of(UUID.fromString(response.getServerId()))
          : Optional.empty();
    } catch (RuntimeException e) {
      throw new AccessCheckUnavailableException("messaging call failed", e);
    }
  }

  // Hỏi Community user có là member của server không
  @Override
  public boolean isServerMember(UUID userId, UUID serverId) {
    try {
      return members
          .withDeadlineAfter(300, TimeUnit.MILLISECONDS)
          .checkServerMembership(
              CheckServerMembershipRequest.newBuilder()
                  .setUserId(userId.toString())
                  .setServerId(serverId.toString())
                  .build())
          .getIsMember();
    } catch (RuntimeException e) {
      throw new AccessCheckUnavailableException("community call failed", e);
    }
  }
}
