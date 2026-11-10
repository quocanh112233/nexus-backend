package com.nexus.presence.application.relation;

import com.nexus.presence.domain.relation.RelationshipProjection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Projection trong bộ nhớ mô phỏng hợp đồng: members luôn cập nhật, tập server/bạn chỉ khi loaded
public class FakeRelationshipProjection implements RelationshipProjection {

  private final Map<UUID, Set<UUID>> servers = new HashMap<>();
  private final Set<UUID> loaded = new HashSet<>();
  private final Map<UUID, Set<UUID>> members = new HashMap<>();
  private final Map<UUID, Set<UUID>> friends = new HashMap<>();
  private final Map<UUID, Long> revisions = new HashMap<>();
  private long epoch;

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

  // Phiên bản của user cộng epoch
  @Override
  public long revision(UUID userId) {
    return revisions.getOrDefault(userId, 0L) + epoch;
  }

  // Thay tập server, tập bạn và đánh dấu đã nạp chỉ khi phiên bản khớp
  @Override
  public boolean load(
      UUID userId, Set<UUID> serverIds, Set<UUID> friendIds, long expectedRevision) {
    if (revision(userId) != expectedRevision) {
      return false;
    }
    servers.put(userId, new HashSet<>(serverIds));
    friends.put(userId, new HashSet<>(friendIds));
    loaded.add(userId);
    serverIds.forEach(s -> members.computeIfAbsent(s, k -> new HashSet<>()).add(userId));
    return true;
  }

  // Tăng phiên bản, thêm user vào members, thêm server cho user nếu đã loaded
  @Override
  public void addServer(UUID userId, UUID serverId) {
    revisions.merge(userId, 1L, Long::sum);
    members.computeIfAbsent(serverId, k -> new HashSet<>()).add(userId);
    if (loaded.contains(userId)) {
      servers.computeIfAbsent(userId, k -> new HashSet<>()).add(serverId);
    }
  }

  // Tăng phiên bản, bỏ user khỏi members, bỏ server của user nếu đã loaded
  @Override
  public void removeServer(UUID userId, UUID serverId) {
    revisions.merge(userId, 1L, Long::sum);
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

  // Lấy và xóa tập members của server, tăng epoch, trả members đã lấy
  @Override
  public Set<UUID> deleteServer(UUID serverId) {
    epoch++;
    Set<UUID> captured = members.remove(serverId);
    return captured == null ? Set.of() : Set.copyOf(captured);
  }

  // Hai user có chung server không
  @Override
  public boolean shareServer(UUID a, UUID b) {
    return serversOf(a).stream().anyMatch(serversOf(b)::contains);
  }

  // Tập bạn của user, rỗng nếu chưa có
  @Override
  public Set<UUID> friendsOf(UUID userId) {
    return Set.copyOf(friends.getOrDefault(userId, Set.of()));
  }

  // Tăng phiên bản cả hai, thêm bạn cho phía nào đã loaded
  @Override
  public void addFriend(UUID a, UUID b) {
    linkFriend(a, b, true);
    linkFriend(b, a, true);
  }

  // Tăng phiên bản cả hai, bỏ bạn ở phía nào đã loaded
  @Override
  public void removeFriend(UUID a, UUID b) {
    linkFriend(a, b, false);
    linkFriend(b, a, false);
  }

  // a có b trong tập bạn không
  @Override
  public boolean isFriend(UUID a, UUID b) {
    return friendsOf(a).contains(b);
  }

  // Tăng phiên bản của user, thêm hoặc bỏ friend trong tập bạn nếu user đã loaded
  private void linkFriend(UUID user, UUID friend, boolean add) {
    revisions.merge(user, 1L, Long::sum);
    if (loaded.contains(user)) {
      Set<UUID> set = friends.computeIfAbsent(user, k -> new HashSet<>());
      if (add) {
        set.add(friend);
      } else {
        set.remove(friend);
      }
    }
  }
}
