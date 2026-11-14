package com.nexus.notification.infrastructure.persistence.readstate;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng read_states, chỉ dùng lệnh native, so sánh uuid ở Postgres
interface ReadStateDao extends Repository<ReadStateJpaEntity, UUID> {

  // Đặt mốc đọc = tin mới nhất cho mọi kênh sống đã có tin của server, giữ dòng có sẵn
  @Modifying
  @Query(
      value =
          "insert into {h-schema}read_states"
              + " (user_id, channel_id, last_read_message_id, mention_count, updated_at)"
              + " select :userId, channel_id, last_message_id, 0, now()"
              + " from {h-schema}channel_activity where server_id = :serverId"
              + " and deleted_at is null and last_message_id is not null"
              + " on conflict do nothing",
      nativeQuery = true)
  void baselineForJoin(@Param("serverId") UUID serverId, @Param("userId") UUID userId);

  // Bước 1 của advance: đẩy mốc đọc lên GREATEST và giữ khóa dòng tới hết transaction
  @Modifying
  @Query(
      value =
          "insert into {h-schema}read_states"
              + " (user_id, channel_id, last_read_message_id, mention_count, updated_at)"
              + " values (:userId, :channelId, :messageId, 0, now())"
              + " on conflict (user_id, channel_id) do update set"
              + " last_read_message_id = greatest(read_states.last_read_message_id,"
              + " excluded.last_read_message_id),"
              + " updated_at = now()",
      nativeQuery = true)
  void advanceLastRead(
      @Param("userId") UUID userId,
      @Param("channelId") UUID channelId,
      @Param("messageId") UUID messageId);

  // Bước 2 của advance: đếm lại mention sau mốc bằng snapshot mới, trả [last_read, mention_count]
  @Query(
      value =
          "update {h-schema}read_states set mention_count ="
              + " (select count(*) from {h-schema}mentions m"
              + " where m.user_id = :userId and m.channel_id = :channelId"
              + " and m.message_id > read_states.last_read_message_id)"
              + " where user_id = :userId and channel_id = :channelId"
              + " returning last_read_message_id, mention_count",
      nativeQuery = true)
  List<Object[]> recountMentions(@Param("userId") UUID userId, @Param("channelId") UUID channelId);

  // Cộng 1 mention nếu tin sau mốc đọc hoặc chưa có mốc, trả mention_count mới
  @Query(
      value =
          "insert into {h-schema}read_states"
              + " (user_id, channel_id, last_read_message_id, mention_count, updated_at)"
              + " values (:userId, :channelId, null, 1, now())"
              + " on conflict (user_id, channel_id) do update set"
              + " mention_count = read_states.mention_count + case when"
              + " read_states.last_read_message_id is null"
              + " or :messageId > read_states.last_read_message_id then 1 else 0 end,"
              + " updated_at = now()"
              + " returning mention_count",
      nativeQuery = true)
  int addMentionCount(
      @Param("userId") UUID userId,
      @Param("channelId") UUID channelId,
      @Param("messageId") UUID messageId);

  // Xóa read state của user ở các kênh của server
  @Modifying
  @Query(
      value =
          "delete from {h-schema}read_states where user_id = :userId and channel_id in"
              + " (select channel_id from {h-schema}channel_activity where server_id = :serverId)",
      nativeQuery = true)
  void deleteForUserInServer(@Param("userId") UUID userId, @Param("serverId") UUID serverId);

  // Xóa read state của mọi user ở các kênh của server
  @Modifying
  @Query(
      value =
          "delete from {h-schema}read_states where channel_id in"
              + " (select channel_id from {h-schema}channel_activity where server_id = :serverId)",
      nativeQuery = true)
  void deleteForServer(@Param("serverId") UUID serverId);

  // Trả [channel_id, server_id, last_message_id, last_read, mention_count, unread] theo D1
  @Query(
      value =
          "select ca.channel_id, ca.server_id, ca.last_message_id, rs.last_read_message_id,"
              + " coalesce(rs.mention_count, 0),"
              + " (rs.last_read_message_id is null"
              + " or ca.last_message_id > rs.last_read_message_id) as unread"
              + " from {h-schema}channel_activity ca"
              + " left join {h-schema}read_states rs"
              + " on rs.channel_id = ca.channel_id and rs.user_id = :userId"
              + " where ca.deleted_at is null and ca.last_message_id is not null"
              + " and (exists (select 1 from {h-schema}server_members sm"
              + " where sm.server_id = ca.server_id and sm.user_id = :userId)"
              + " or exists (select 1 from {h-schema}dm_participants dp"
              + " where dp.channel_id = ca.channel_id and dp.user_id = :userId))"
              + " order by ca.channel_id",
      nativeQuery = true)
  List<Object[]> listFor(@Param("userId") UUID userId);
}
