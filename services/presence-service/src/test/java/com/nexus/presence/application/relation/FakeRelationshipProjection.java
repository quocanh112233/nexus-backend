package com.nexus.presence.application.relation;

import com.nexus.presence.domain.relation.RelationshipProjection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Projection trong bộ nhớ mô phỏng hợp đồng: members luôn cập nhật, tập server chỉ khi đã loaded
public class FakeRelationshipProjection implements RelationshipProjection {

  private final Map<UUID, Set<UUID>> servers = new HashMap<>();
  private final Set<UUID> loaded = new HashSet<>();
  private final Map<UUID, Set<UUID>> members = new HashMap<>();

  // Tập server của user, rỗng nếu chưa có
  @Override
  public Set<UUID> serversOf(UUID userId) {
    return Set.copyOf(servers.getOrDefault(userId, Set.of()));
  }

  // User đã nạp đủ chưa
  @Override
  public boolean isLoaded(UUID userId) {
    return loaded.contains(userId);
  }

  // Thay tập server và đánh dấu đã nạp
  @Override
  public void load(UUID userId, Set<UUID> serverIds) {
    servers.put(userId, new HashSet<>(serverIds));
    loaded.add(userId);
    serverIds.forEach(s -> members.computeIfAbsent(s, k -> new HashSet<>()).add(userId));
  }

  // Thêm user vào members, thêm server cho user nếu đã loaded
  @Override
  public void addServer(UUID userId, UUID serverId) {
    members.computeIfAbsent(serverId, k -> new HashSet<>()).add(userId);
    if (loaded.contains(userId)) {
      servers.computeIfAbsent(userId, k -> new HashSet<>()).add(serverId);
    }
  }

  // Bỏ user khỏi members, bỏ server của user nếu đã loaded
  @Override
  public void removeServer(UUID userId, UUID serverId) {
    members.getOrDefault(serverId, new HashSet<>()).remove(userId);
    if (loaded.contains(userId)) {
      servers.getOrDefault(userId, new HashSet<>()).remove(serverId);
    }
  }

  // Các user được ghi nhận trong server
  @Override
  public Set<UUID> membersOf(UUID serverId) {
    return Set.copyOf(members.getOrDefault(serverId, Set.of()));
  }

  // Xóa tập members của server
  @Override
  public void deleteServer(UUID serverId) {
    members.remove(serverId);
  }

  // Hai user có chung server không
  @Override
  public boolean shareServer(UUID a, UUID b) {
    return serversOf(a).stream().anyMatch(serversOf(b)::contains);
  }
}
