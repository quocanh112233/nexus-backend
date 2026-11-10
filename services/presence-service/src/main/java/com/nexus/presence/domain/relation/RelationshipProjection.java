package com.nexus.presence.domain.relation;

import java.util.Set;
import java.util.UUID;

// Projection quan hệ user-server và bạn bè: event luôn tăng rev, tập của user chỉ ghi khi loaded
public interface RelationshipProjection {

  // Tập server của user
  Set<UUID> serversOf(UUID userId);

  // User đã nạp đủ danh sách server chưa
  boolean isLoaded(UUID userId);

  // Phiên bản quan hệ của user (rev của user cộng epoch), chỉ tăng mỗi khi có event áp vào
  long revision(UUID userId);

  // Thay cả tập server lẫn tập bạn và đánh dấu đã nạp chỉ khi phiên bản còn bằng expectedRevision
  boolean load(UUID userId, Set<UUID> serverIds, Set<UUID> friendIds, long expectedRevision);

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

  // Tập bạn của user
  Set<UUID> friendsOf(UUID userId);

  // Nguyên tử: tăng rev cả hai, thêm bạn vào tập của phía nào đã loaded
  void addFriend(UUID a, UUID b);

  // Nguyên tử: tăng rev cả hai, bỏ bạn khỏi tập của phía nào đã loaded
  void removeFriend(UUID a, UUID b);

  // b có trong tập bạn của a không
  boolean isFriend(UUID a, UUID b);
}
