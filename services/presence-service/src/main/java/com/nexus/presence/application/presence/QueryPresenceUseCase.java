package com.nexus.presence.application.presence;

import com.nexus.presence.application.relation.EnsureLoadedUseCase;
import com.nexus.presence.application.relation.RelationUnavailableException;
import com.nexus.presence.domain.presence.PresenceStatus;
import com.nexus.presence.domain.presence.SessionStore;
import com.nexus.presence.domain.relation.PresenceAudiencePolicy;
import com.nexus.presence.domain.relation.RelationshipProjection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Trả trạng thái của các target mà viewer được thấy, lỗi nạp quan hệ thì coi là không thấy
@Service
public class QueryPresenceUseCase {

  private final EnsureLoadedUseCase ensure;
  private final RelationshipProjection projection;
  private final SessionStore store;

  // Nhận use case nạp quan hệ, projection và kho session
  public QueryPresenceUseCase(
      EnsureLoadedUseCase ensure, RelationshipProjection projection, SessionStore store) {
    this.ensure = ensure;
    this.projection = projection;
    this.store = store;
  }

  // Giữ target được thấy theo thứ tự đầu vào; viewer lỗi thì rỗng; viewer không có server thì chỉ
  // thấy mình
  public Map<UUID, PresenceStatus> execute(UUID viewer, List<UUID> targets) {
    Map<UUID, PresenceStatus> result = new LinkedHashMap<>();
    try {
      ensure.ensure(viewer);
    } catch (RelationUnavailableException e) {
      return result;
    }
    boolean noServers = projection.serversOf(viewer).isEmpty();
    boolean loaderDown = false;
    // ponytail: target lạ chưa nạp tạo khóa loaded 5 phút (tập rỗng), chặn bởi rate limit gateway
    for (UUID target : targets) {
      if (!target.equals(viewer)) {
        if (noServers || (loaderDown && !projection.isLoaded(target))) {
          continue;
        }
        try {
          ensure.ensure(target);
        } catch (RelationUnavailableException e) {
          loaderDown = true;
          continue;
        }
      }
      if (PresenceAudiencePolicy.canSee(viewer, target, projection)) {
        result.put(target, store.isOnline(target) ? PresenceStatus.ONLINE : PresenceStatus.OFFLINE);
      }
    }
    return result;
  }
}
