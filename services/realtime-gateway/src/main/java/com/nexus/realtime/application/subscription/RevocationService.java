package com.nexus.realtime.application.subscription;

import com.nexus.common.messaging.EventEnvelope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

// Thu hồi subscription cục bộ khi quyền đổi, xóa khỏi sổ trước rồi mới gỡ khỏi broker
@Component
public class RevocationService {

  private static final String CHANNELS = "/topic/channels/";

  private final SubscriptionRegistry registry;
  private final AccessChecker accessChecker;
  private final SubscriptionRevoker revoker;

  // Nhận sổ subscription, cổng kiểm quyền (service-token) và cổng gỡ subscription
  public RevocationService(
      SubscriptionRegistry registry, AccessChecker accessChecker, SubscriptionRevoker revoker) {
    this.registry = registry;
    this.accessChecker = accessChecker;
    this.revoker = revoker;
  }

  // Rời, kick, ban, xóa server thì thu hồi vô điều kiện, đổi quyền thì re-check đúng phạm vi server
  public void onEvent(EventEnvelope envelope) {
    Map<String, Object> payload = envelope.payload();
    String reason = envelope.eventType();
    switch (reason) {
      case "MemberLeft", "MemberKicked", "MemberBanned" -> {
        UUID user = id(payload, "userId");
        UUID server = id(payload, "serverId");
        registry.byUser(user).stream()
            .filter(s -> server.equals(s.serverId()))
            .forEach(s -> revoke(s, reason));
      }
      case "MemberRolesChanged" -> {
        UUID user = id(payload, "userId");
        UUID server = id(payload, "serverId");
        recheck(
            registry.byUser(user).stream()
                .filter(s -> server.equals(s.serverId()) && isChannel(s))
                .toList(),
            reason);
      }
      case "ChannelPermissionsChanged" ->
          recheck(registry.byDestination(CHANNELS + id(payload, "channelId")), reason);
      case "RoleUpdated", "RoleDeleted" ->
          recheck(
              registry.byServer(id(payload, "serverId")).stream()
                  .filter(RevocationService::isChannel)
                  .toList(),
              reason);
      case "ServerDeleted" ->
          registry.byServer(id(payload, "serverId")).forEach(s -> revoke(s, reason));
      case "ChannelDeleted" ->
          registry
              .byDestination(CHANNELS + id(payload, "channelId"))
              .forEach(s -> revoke(s, reason));
      default -> {}
    }
  }

  // Subscription có phải topic kênh không
  private static boolean isChannel(SessionSubscription s) {
    return s.destination().startsWith(CHANNELS);
  }

  // Hỏi lại quyền tuần tự, lỗi hạ tầng đầu tiên thì thu hồi nốt phần còn lại mà không hỏi nữa
  private void recheck(List<SessionSubscription> subscriptions, String reason) {
    boolean down = false;
    for (SessionSubscription s : subscriptions) {
      if (!down) {
        try {
          if (stillAllowed(s)) {
            continue;
          }
        } catch (AccessCheckUnavailableException e) {
          down = true;
        }
      }
      revoke(s, reason);
    }
  }

  // Subscription kênh còn xem được không, lỗi hạ tầng ném AccessCheckUnavailableException
  private boolean stillAllowed(SessionSubscription s) {
    UUID channelId = SubscriptionRule.parse(s.destination()).id();
    return accessChecker.canViewChannel(s.userId(), channelId).isPresent();
  }

  // Xóa đúng subscription này khỏi sổ trước (nếu đã bị thay thì bỏ qua), rồi mới gỡ khỏi broker
  private void revoke(SessionSubscription s, String reason) {
    if (registry.remove(s)) {
      revoker.revoke(s, reason);
    }
  }

  // Đọc một id UUID bắt buộc từ payload
  private static UUID id(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Payload thiếu " + key);
    }
    return UUID.fromString(value.toString());
  }
}
