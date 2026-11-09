package com.nexus.presence.application.relation;

import com.nexus.presence.domain.relation.RelationshipProjection;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Đảm bảo projection của user đã nạp đủ, chưa thì nạp bù từ Community
@Service
public class EnsureLoadedUseCase {

  private static final int MAX_ATTEMPTS = 3;

  private final RelationshipProjection projection;
  private final ServerListLoader loader;

  // Nhận projection và loader
  public EnsureLoadedUseCase(RelationshipProjection projection, ServerListLoader loader) {
    this.projection = projection;
    this.loader = loader;
  }

  // Chưa loaded thì đọc phiên bản, lấy danh sách từ loader rồi load có rào, quá 3 lần thì ném lỗi
  public void ensure(UUID userId) {
    if (projection.isLoaded(userId)) {
      return;
    }
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      long revision = projection.revision(userId);
      if (projection.load(userId, loader.serverIdsOf(userId), revision)) {
        return;
      }
    }
    throw new RelationUnavailableException("relation snapshot kept going stale", null);
  }
}
