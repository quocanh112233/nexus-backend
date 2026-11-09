package com.nexus.presence.application.relation;

import com.nexus.presence.domain.relation.RelationshipProjection;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Đảm bảo projection của user đã nạp đủ, chưa thì nạp bù từ Community
@Service
public class EnsureLoadedUseCase {

  private final RelationshipProjection projection;
  private final ServerListLoader loader;

  // Nhận projection và loader
  public EnsureLoadedUseCase(RelationshipProjection projection, ServerListLoader loader) {
    this.projection = projection;
    this.loader = loader;
  }

  // Chưa loaded thì lấy danh sách server từ loader rồi load
  public void ensure(UUID userId) {
    if (!projection.isLoaded(userId)) {
      projection.load(userId, loader.serverIdsOf(userId));
    }
  }
}
