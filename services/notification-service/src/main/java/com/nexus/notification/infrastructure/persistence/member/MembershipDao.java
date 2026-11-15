package com.nexus.notification.infrastructure.persistence.member;

import com.nexus.notification.infrastructure.persistence.readstate.ReadStateJpaEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng server_members và dm_participants, chỉ dùng lệnh native
interface MembershipDao extends Repository<ReadStateJpaEntity, UUID> {

  // Thêm thành viên server, bỏ qua nếu đã có
  @Modifying
  @Query(
      value =
          "insert into {h-schema}server_members (server_id, user_id, joined_at)"
              + " values (:serverId, :userId, :joinedAt) on conflict do nothing",
      nativeQuery = true)
  void addServerMember(
      @Param("serverId") UUID serverId,
      @Param("userId") UUID userId,
      @Param("joinedAt") Instant joinedAt);

  // Xóa một thành viên khỏi server
  @Modifying
  @Query(
      value =
          "delete from {h-schema}server_members where server_id = :serverId and user_id = :userId",
      nativeQuery = true)
  void removeServerMember(@Param("serverId") UUID serverId, @Param("userId") UUID userId);

  // Xóa mọi thành viên của server
  @Modifying
  @Query(
      value = "delete from {h-schema}server_members where server_id = :serverId",
      nativeQuery = true)
  void removeServer(@Param("serverId") UUID serverId);

  // Ghi hai participant của kênh DM, bỏ qua dòng đã có
  @Modifying
  @Query(
      value =
          "insert into {h-schema}dm_participants (channel_id, user_id)"
              + " values (:channelId, :first), (:channelId, :second) on conflict do nothing",
      nativeQuery = true)
  void addDmParticipants(
      @Param("channelId") UUID channelId, @Param("first") UUID first, @Param("second") UUID second);

  // True nếu user là thành viên server đã cho hoặc participant DM của kênh
  @Query(
      value =
          "select exists (select 1 from {h-schema}server_members"
              + " where server_id = cast(:serverId as uuid) and user_id = :userId)"
              + " or exists (select 1 from {h-schema}dm_participants"
              + " where channel_id = :channelId and user_id = :userId)",
      nativeQuery = true)
  boolean canAccess(
      @Param("userId") UUID userId,
      @Param("channelId") UUID channelId,
      @Param("serverId") UUID serverId);

  // True nếu user vào server từ lúc at trở về trước hoặc là participant DM của kênh
  @Query(
      value =
          "select exists (select 1 from {h-schema}server_members"
              + " where server_id = cast(:serverId as uuid) and user_id = :userId"
              + " and joined_at <= :at)"
              + " or exists (select 1 from {h-schema}dm_participants"
              + " where channel_id = :channelId and user_id = :userId)",
      nativeQuery = true)
  boolean wasMemberAt(
      @Param("userId") UUID userId,
      @Param("channelId") UUID channelId,
      @Param("serverId") UUID serverId,
      @Param("at") Instant at);
}
