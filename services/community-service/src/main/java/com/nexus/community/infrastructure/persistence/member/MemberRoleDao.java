package com.nexus.community.infrastructure.persistence.member;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng member_roles, lệnh ghi chạy trong transaction của nơi gọi
interface MemberRoleDao extends JpaRepository<MemberRoleJpaEntity, MemberRoleJpaEntity.Key> {

  // Role của nhiều thành viên trong một server bằng một query, sắp theo role_id cho ổn định
  @Query(
      "select e from MemberRoleJpaEntity e where e.id.serverId = :serverId"
          + " and e.id.userId in :userIds order by e.id.roleId")
  List<MemberRoleJpaEntity> findAllByServerIdAndUserIdIn(
      @Param("serverId") UUID serverId, @Param("userIds") Collection<UUID> userIds);

  // Xóa hết role của một thành viên, chỉ xả trước, không làm sạch vì entity chỉ có khóa
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          "delete from {h-schema}member_roles where server_id = :serverId and user_id = :userId",
      nativeQuery = true)
  int deleteAllFor(@Param("serverId") UUID serverId, @Param("userId") UUID userId);

  // Thêm một role cho thành viên
  @Modifying
  @Query(
      value =
          "insert into {h-schema}member_roles (server_id, user_id, role_id)"
              + " values (:serverId, :userId, :roleId)",
      nativeQuery = true)
  int insert(
      @Param("serverId") UUID serverId, @Param("userId") UUID userId, @Param("roleId") UUID roleId);
}
