package com.nexus.notification.infrastructure.grpc;

import com.google.protobuf.Timestamp;
import com.nexus.notification.application.port.BackfillSourceUnavailableException;
import com.nexus.notification.application.port.MembershipSnapshotSource;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import com.nexus.proto.community.v1.StreamAllMembersRequest;
import com.nexus.proto.messaging.v1.SocialServiceGrpc;
import com.nexus.proto.messaging.v1.StreamAllDmChannelsRequest;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// Đọc snapshot membership qua stream gRPC có deadline mỗi RPC, lỗi gRPC thành không-khả-dụng
public class GrpcMembershipSnapshotSource implements MembershipSnapshotSource {

  private final PermissionServiceGrpc.PermissionServiceBlockingStub community;
  private final SocialServiceGrpc.SocialServiceBlockingStub messaging;
  private final long deadlineMillis;

  // Nhận kênh tới Community, Messaging và deadline tổng của mỗi RPC stream
  public GrpcMembershipSnapshotSource(Channel community, Channel messaging, Duration deadline) {
    this.community = PermissionServiceGrpc.newBlockingStub(community);
    this.messaging = SocialServiceGrpc.newBlockingStub(messaging);
    this.deadlineMillis = deadline.toMillis();
  }

  // Stream StreamAllMembers, đổi từng MemberRecord sang MemberSnapshot với joinedAt thật
  @Override
  public void forEachMember(Consumer<MemberSnapshot> sink) {
    try {
      community
          .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
          .streamAllMembers(StreamAllMembersRequest.getDefaultInstance())
          .forEachRemaining(
              r ->
                  sink.accept(
                      new MemberSnapshot(
                          new ServerId(UUID.fromString(r.getServerId())),
                          new UserId(UUID.fromString(r.getUserId())),
                          instant(r.getJoinedAt()))));
    } catch (StatusRuntimeException e) {
      throw new BackfillSourceUnavailableException("community stream failed", e);
    }
  }

  // Stream StreamAllDmChannels, đổi từng DmChannelRecord sang DmSnapshot
  @Override
  public void forEachDm(Consumer<DmSnapshot> sink) {
    try {
      messaging
          .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
          .streamAllDmChannels(StreamAllDmChannelsRequest.getDefaultInstance())
          .forEachRemaining(
              r ->
                  sink.accept(
                      new DmSnapshot(
                          new ChannelId(UUID.fromString(r.getChannelId())),
                          new UserId(UUID.fromString(r.getUserLowId())),
                          new UserId(UUID.fromString(r.getUserHighId())))));
    } catch (StatusRuntimeException e) {
      throw new BackfillSourceUnavailableException("messaging stream failed", e);
    }
  }

  // Đổi Timestamp protobuf sang Instant
  private static Instant instant(Timestamp ts) {
    return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
  }
}
