package com.nexus.presence.application.presence;

import com.nexus.presence.domain.presence.PresenceStatus;
import com.nexus.presence.domain.presence.SessionStore;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Xử lý event session của gateway, chỉ phát PresenceChanged khi trạng thái thật sự đổi
@Service
public class SessionService {

  private static final long GRACE_MILLIS = 2000;

  private final SessionStore store;
  private final PresenceChangeNotifier notifier;

  // Nhận kho session và bộ phát thay đổi
  public SessionService(SessionStore store, PresenceChangeNotifier notifier) {
    this.store = store;
    this.notifier = notifier;
  }

  // Ghi session, user từ 0 lên 1 session thì phát ONLINE
  public void connect(String sessionId, UUID userId, String instanceId, Instant connectedAt) {
    if (store.connect(sessionId, userId, instanceId, connectedAt)) {
      notifier.changed(userId, PresenceStatus.ONLINE);
    }
  }

  // Bỏ session, user về 0 session thì phát OFFLINE
  public void disconnect(String sessionId) {
    store.disconnect(sessionId).ifPresent(u -> notifier.changed(u, PresenceStatus.OFFLINE));
  }

  // Gia hạn instance, nối session chưa biết, ngắt session vắng (chừa 2 s; userId null thì bỏ qua)
  public void heartbeat(String instanceId, Map<String, UUID> sessions, Instant sentAt) {
    store.markAlive(instanceId);
    sessions.forEach(
        (sid, uid) -> {
          if (uid != null) {
            connect(sid, uid, instanceId, sentAt);
          }
        });
    // ponytail: Lua tuần tự + gRPC ensure mỗi session bù trên thread listener; nâng: batch+giới hạn
    store.sessionsOf(instanceId, sentAt.minusMillis(GRACE_MILLIS)).stream()
        .filter(sid -> !sessions.containsKey(sid))
        .forEach(this::disconnect);
  }
}
