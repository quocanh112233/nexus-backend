package com.nexus.presence.application.relation;

import com.nexus.presence.domain.relation.RelationshipProjection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Đảm bảo projection của user đã nạp đủ, chưa thì nạp bù server từ Community và bạn từ Messaging
@Service
public class EnsureLoadedUseCase {

  private static final int MAX_ATTEMPTS = 3;

  private final RelationshipProjection projection;
  private final ServerListLoader loader;
  private final FriendListLoader friendLoader;

  // Nhận projection, loader server và loader bạn
  public EnsureLoadedUseCase(
      RelationshipProjection projection, ServerListLoader loader, FriendListLoader friendLoader) {
    this.projection = projection;
    this.loader = loader;
    this.friendLoader = friendLoader;
  }

  // Chưa loaded thì đọc phiên bản, lấy server và bạn rồi load có rào, quá 3 lần thì ném lỗi
  public void ensure(UUID userId) {
    if (projection.isLoaded(userId)) {
      return;
    }
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      long revision = projection.revision(userId);
      Set<UUID> servers = loader.serverIdsOf(userId);
      if (projection.load(userId, servers, friendLoader.friendIdsOf(userId), revision)) {
        return;
      }
    }
    throw new RelationUnavailableException("relation snapshot kept going stale", null);
  }
}
