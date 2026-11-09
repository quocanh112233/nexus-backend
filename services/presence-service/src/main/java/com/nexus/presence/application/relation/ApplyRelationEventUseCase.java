package com.nexus.presence.application.relation;

import com.nexus.presence.domain.relation.RelationshipProjection;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Áp event member và server của community vào projection, event lạ bị bỏ qua
@Service
public class ApplyRelationEventUseCase {

  private final RelationshipProjection projection;

  // Nhận projection
  public ApplyRelationEventUseCase(RelationshipProjection projection) {
    this.projection = projection;
  }

  // Join thì add; left, kicked, banned thì remove; ServerDeleted gỡ server khỏi từng member
  public void execute(String eventType, UUID serverId, UUID userId) {
    switch (eventType) {
      case "MemberJoined" -> projection.addServer(userId, serverId);
      case "MemberLeft", "MemberKicked", "MemberBanned" ->
          projection.removeServer(userId, serverId);
      case "ServerDeleted" ->
          projection.deleteServer(serverId).forEach(m -> projection.removeServer(m, serverId));
      default -> {}
    }
  }
}
