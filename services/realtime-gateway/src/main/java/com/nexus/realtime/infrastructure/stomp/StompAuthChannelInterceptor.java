package com.nexus.realtime.infrastructure.stomp;

import com.nexus.realtime.application.subscription.AccessCheckUnavailableException;
import com.nexus.realtime.application.subscription.AccessChecker;
import com.nexus.realtime.application.subscription.SessionSubscription;
import com.nexus.realtime.application.subscription.SubscriptionRegistry;
import com.nexus.realtime.application.subscription.SubscriptionRule;
import com.nexus.realtime.application.subscription.SubscriptionRule.Kind;
import com.nexus.realtime.application.subscription.SubscriptionRule.Target;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

// Xác thực CONNECT bằng JWT và kiểm quyền SUBSCRIBE/SEND trên kênh nhận frame từ client
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

  private final JwtDecoder jwtDecoder;
  private final AccessChecker accessChecker;
  private final SubscriptionRegistry registry;

  // Nhận decoder JWT, cổng kiểm quyền và sổ subscription
  public StompAuthChannelInterceptor(
      JwtDecoder jwtDecoder, AccessChecker accessChecker, SubscriptionRegistry registry) {
    this.jwtDecoder = jwtDecoder;
    this.accessChecker = accessChecker;
    this.registry = registry;
  }

  // Xử lý theo lệnh STOMP, từ chối bằng MessagingException mang lý do để Spring gửi ERROR rồi đóng
  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    StompCommand command = accessor == null ? null : accessor.getCommand();
    if (command == null) {
      return message;
    }
    switch (command) {
      case CONNECT -> connect(accessor);
      case DISCONNECT -> registry.removeSession(accessor.getSessionId());
      case SUBSCRIBE -> subscribe(accessor);
      case UNSUBSCRIBE -> registry.remove(accessor.getSessionId(), accessor.getSubscriptionId());
      case SEND -> send(accessor);
      default -> throw deny("PERMISSION_DENIED");
    }
    return message;
  }

  // Verify Bearer JWT một lần (ponytail: hạn token chỉ kiểm lúc CONNECT, có chủ đích)
  private void connect(StompHeaderAccessor accessor) {
    String header = accessor.getFirstNativeHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      throw deny("UNAUTHENTICATED");
    }
    try {
      String sub = jwtDecoder.decode(header.substring(7)).getSubject();
      if (sub == null) {
        throw deny("UNAUTHENTICATED");
      }
      UUID.fromString(sub);
      accessor.setUser(new UsernamePasswordAuthenticationToken(sub, null, List.of()));
    } catch (JwtException | IllegalArgumentException e) {
      throw deny("UNAUTHENTICATED");
    }
  }

  // Kiểm quyền, fail-closed (ponytail: gRPC chặn thread WS tới 500 ms, dùng virtual thread nếu cần)
  private void subscribe(StompHeaderAccessor accessor) {
    UUID userId = userOf(accessor);
    String sessionId = accessor.getSessionId();
    String subscriptionId = accessor.getSubscriptionId();
    if (subscriptionId == null || registry.exists(sessionId, subscriptionId)) {
      throw deny("DUPLICATE_SUBSCRIPTION_ID");
    }
    Target target = parse(accessor.getDestination());
    UUID serverId = null;
    try {
      serverId =
          switch (target.kind()) {
            case CHANNEL -> accessChecker.canViewChannel(userId, target.id()).orElse(null);
            case SERVER -> accessChecker.isServerMember(userId, target.id()) ? target.id() : null;
            case USER_EVENTS -> null;
            case TYPING_SEND -> throw deny("INVALID_DESTINATION");
          };
    } catch (AccessCheckUnavailableException e) {
      throw deny("DEPENDENCY_UNAVAILABLE");
    }
    if (serverId == null && target.kind() != Kind.USER_EVENTS) {
      throw deny("PERMISSION_DENIED");
    }
    if (!registry.add(
        new SessionSubscription(
            sessionId, userId, subscriptionId, accessor.getDestination(), serverId))) {
      throw deny("DUPLICATE_SUBSCRIPTION_ID");
    }
  }

  // Chỉ cho user đã xác thực gửi tới /app/channels/{id}/typing
  private void send(StompHeaderAccessor accessor) {
    userOf(accessor);
    if (parse(accessor.getDestination()).kind() != Kind.TYPING_SEND) {
      throw deny("INVALID_DESTINATION");
    }
  }

  // Lấy userId từ Principal của session, chưa CONNECT thì từ chối
  private static UUID userOf(StompHeaderAccessor accessor) {
    Principal user = accessor.getUser();
    if (user == null) {
      throw deny("UNAUTHENTICATED");
    }
    return UUID.fromString(user.getName());
  }

  // Phân tích destination, sai thì từ chối INVALID_DESTINATION
  private static Target parse(String destination) {
    try {
      return SubscriptionRule.parse(destination);
    } catch (IllegalArgumentException e) {
      throw deny("INVALID_DESTINATION");
    }
  }

  // Ngoại lệ từ chối mà message là mã lý do, được Spring đưa vào header message của ERROR
  private static MessagingException deny(String reason) {
    return new MessagingException(reason);
  }
}
