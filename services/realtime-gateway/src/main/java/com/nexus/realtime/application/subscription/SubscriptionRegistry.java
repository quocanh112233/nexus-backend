package com.nexus.realtime.application.subscription;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

// Sổ subscription trong bộ nhớ, an toàn đa luồng, khóa theo session rồi subscription
@Component
public class SubscriptionRegistry {

  private final Map<String, Map<String, SessionSubscription>> bySession = new ConcurrentHashMap<>();
  private final Set<SessionSubscription> suspended = ConcurrentHashMap.newKeySet();

  // Ghi subscription, trả false nếu (sessionId, subscriptionId) đã có để không ghi đè
  public boolean add(SessionSubscription subscription) {
    boolean[] added = {false};
    bySession.compute(
        subscription.sessionId(),
        (k, subs) -> {
          Map<String, SessionSubscription> map = subs == null ? new ConcurrentHashMap<>() : subs;
          added[0] = map.putIfAbsent(subscription.subscriptionId(), subscription) == null;
          return map;
        });
    return added[0];
  }

  // Đã có subscription với cặp (sessionId, subscriptionId) chưa
  public boolean exists(String sessionId, String subscriptionId) {
    Map<String, SessionSubscription> subs = bySession.get(sessionId);
    return subs != null && subs.containsKey(subscriptionId);
  }

  // Đã có và không bị tạm dừng thì mới được giao frame
  public boolean isDeliverable(String sessionId, String subscriptionId) {
    Map<String, SessionSubscription> subs = bySession.get(sessionId);
    SessionSubscription s = subs == null ? null : subs.get(subscriptionId);
    return s != null && !suspended.contains(s);
  }

  // Tạm dừng giao frame cho các subscription còn đúng giá trị trong sổ, bỏ qua cái đã đổi hoặc mất
  public void suspend(Collection<SessionSubscription> subscriptions) {
    for (SessionSubscription s : subscriptions) {
      bySession.computeIfPresent(
          s.sessionId(),
          (k, subs) -> {
            if (s.equals(subs.get(s.subscriptionId()))) {
              suspended.add(s);
            }
            return subs;
          });
    }
  }

  // Cho giao frame lại, chỉ tác động tới đúng giá trị đã bị tạm dừng
  public void resume(SessionSubscription expected) {
    suspended.remove(expected);
  }

  // Xóa một subscription của session
  public void remove(String sessionId, String subscriptionId) {
    bySession.computeIfPresent(
        sessionId,
        (k, subs) -> {
          SessionSubscription old = subs.remove(subscriptionId);
          if (old != null) {
            suspended.remove(old);
          }
          return subs.isEmpty() ? null : subs;
        });
  }

  // Xóa subscription nếu sổ còn đúng giá trị này, trả false khi đã bị xóa hoặc thay bằng cái khác
  public boolean remove(SessionSubscription expected) {
    boolean[] removed = {false};
    bySession.computeIfPresent(
        expected.sessionId(),
        (k, subs) -> {
          removed[0] = subs.remove(expected.subscriptionId(), expected);
          suspended.remove(expected);
          return subs.isEmpty() ? null : subs;
        });
    return removed[0];
  }

  // Xóa mọi subscription của session
  public void removeSession(String sessionId) {
    bySession.computeIfPresent(
        sessionId,
        (k, subs) -> {
          suspended.removeAll(subs.values());
          return null;
        });
  }

  // Mọi subscription tới destination (ponytail: quét toàn bộ, thêm chỉ mục nếu số lượng lớn)
  public List<SessionSubscription> byDestination(String destination) {
    return all().filter(s -> s.destination().equals(destination)).toList();
  }

  // Mọi subscription có destination bắt đầu bằng prefix
  public List<SessionSubscription> byDestinationPrefix(String prefix) {
    return all().filter(s -> s.destination().startsWith(prefix)).toList();
  }

  // Mọi subscription gắn với server (topic server và các kênh của nó)
  public List<SessionSubscription> byServer(UUID serverId) {
    return all().filter(s -> serverId.equals(s.serverId())).toList();
  }

  // Mọi subscription của session
  public List<SessionSubscription> bySession(String sessionId) {
    Map<String, SessionSubscription> subs = bySession.get(sessionId);
    return subs == null ? List.of() : List.copyOf(subs.values());
  }

  // Mọi subscription của user trên mọi session
  public List<SessionSubscription> byUser(UUID userId) {
    return all().filter(s -> s.userId().equals(userId)).toList();
  }

  // Dòng phẳng mọi subscription hiện có
  private Stream<SessionSubscription> all() {
    return bySession.values().stream().flatMap(m -> m.values().stream());
  }
}
