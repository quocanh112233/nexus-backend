package com.nexus.presence.domain.relation;

import java.util.Set;
import java.util.UUID;

// Projection quan hệ user-server: add/remove luôn ghi members, tập server chỉ ghi khi user loaded
public interface RelationshipProjection {

  // Tập server của user
  Set<UUID> serversOf(UUID userId);

  // User đã nạp đủ danh sách server chưa
  boolean isLoaded(UUID userId);

  // Phiên bản quan hệ của user (rev của user cộng epoch), chỉ tăng mỗi khi có event áp vào
  long revision(UUID userId);

  // Thay toàn bộ tập server và đánh dấu đã nạp chỉ khi phiên bản còn bằng expectedRevision
  boolean load(UUID userId, Set<UUID> serverIds, long expectedRevision);

  // Ghi user vào members của server, thêm server cho user nếu đã loaded
  void addServer(UUID userId, UUID serverId);

  // Bỏ user khỏi members của server, bỏ server của user nếu đã loaded
  void removeServer(UUID userId, UUID serverId);

  // Các user được ghi nhận là member của server
  Set<UUID> membersOf(UUID serverId);

  // Nguyên tử: lấy members, xóa tập members và tăng epoch; trả về members đã lấy
  Set<UUID> deleteServer(UUID serverId);

  // Hai user có chung ít nhất một server không
  boolean shareServer(UUID a, UUID b);
}
