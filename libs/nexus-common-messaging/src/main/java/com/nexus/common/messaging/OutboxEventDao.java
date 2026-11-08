package com.nexus.common.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Repository Spring Data cho bảng outbox_event, có truy vấn khóa batch cho relay
public interface OutboxEventDao extends JpaRepository<OutboxEventJpaEntity, UUID> {

  // Khóa tối đa limit dòng chưa publish theo thứ tự id, bỏ qua dòng relay khác đang giữ
  @Query(
      value =
          "select * from {h-schema}outbox_event where published_at is null order by id limit"
              + " :limit for update skip locked",
      nativeQuery = true)
  List<OutboxEventJpaEntity> lockUnpublishedBatch(@Param("limit") int limit);

  // Đếm dòng chưa publish
  long countByPublishedAtIsNull();

  // Xóa dòng đã publish trước mốc before, trả số dòng đã xóa
  @Modifying
  @Query("delete from OutboxEventJpaEntity e where e.publishedAt < :before")
  int deletePublishedBefore(@Param("before") Instant before);
}
