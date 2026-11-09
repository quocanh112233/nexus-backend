package com.nexus.presence.application.presence;

import com.nexus.presence.domain.presence.PresenceStatus;
import com.nexus.presence.domain.presence.SessionStore;
import org.springframework.stereotype.Service;

// Dọn session của instance mất heartbeat và phát OFFLINE cho user vừa về 0 session
@Service
public class PurgeDeadInstancesUseCase {

  private final SessionStore store;
  private final PresenceChangeNotifier notifier;

  // Nhận kho session và bộ phát thay đổi
  public PurgeDeadInstancesUseCase(SessionStore store, PresenceChangeNotifier notifier) {
    this.store = store;
    this.notifier = notifier;
  }

  // Với mỗi instance chết: purge rồi phát OFFLINE cho từng user trả về
  public void execute() {
    for (String instanceId : store.deadInstances()) {
      store.purge(instanceId).forEach(u -> notifier.changed(u, PresenceStatus.OFFLINE));
    }
  }
}
