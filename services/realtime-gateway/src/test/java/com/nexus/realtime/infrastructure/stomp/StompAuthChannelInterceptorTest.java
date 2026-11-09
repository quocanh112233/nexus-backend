package com.nexus.realtime.infrastructure.stomp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.realtime.application.subscription.AccessChecker;
import com.nexus.realtime.application.subscription.SessionSubscription;
import com.nexus.realtime.application.subscription.SubscriptionRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

// Kiểm tra StompAuthChannelInterceptor với JWT có sub bất thường
class StompAuthChannelInterceptorTest {

  // Kịch bản: JWT hợp lệ chữ ký nhưng sub thiếu, rỗng hoặc không phải UUID
  // Input: CONNECT với decoder trả Jwt có sub null, chuỗi rỗng hoặc not-a-uuid
  // Output: ném MessagingException với lý do UNAUTHENTICATED
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "not-a-uuid"})
  void connectWithBadSubjectIsUnauthenticated(String subject) {
    Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").claim("k", "v");
    JwtDecoder decoder = mock(JwtDecoder.class);
    when(decoder.decode("t"))
        .thenReturn(subject == null ? jwt.build() : jwt.subject(subject).build());
    StompAuthChannelInterceptor interceptor =
        new StompAuthChannelInterceptor(
            decoder, mock(AccessChecker.class), new SubscriptionRegistry());
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "Bearer t");
    Message<byte[]> message =
        MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    assertThatThrownBy(() -> interceptor.preSend(message, null))
        .isInstanceOf(MessagingException.class)
        .hasMessage("UNAUTHENTICATED");
  }

  // Kịch bản: SUBSCRIBE trùng subscription id trên destination khác
  // Input: SUBSCRIBE id s1 kênh A được phép rồi SUBSCRIBE id s1 kênh B được phép
  // Output: lần hai ném DUPLICATE_SUBSCRIPTION_ID và sổ chỉ còn kênh A
  @Test
  void reusedSubscriptionIdKeepsFirstInRegistry() {
    AccessChecker checker = mock(AccessChecker.class);
    when(checker.canViewChannel(any(), any())).thenReturn(Optional.of(UUID.randomUUID()));
    SubscriptionRegistry registry = new SubscriptionRegistry();
    StompAuthChannelInterceptor interceptor =
        new StompAuthChannelInterceptor(mock(JwtDecoder.class), checker, registry);
    UUID user = UUID.randomUUID();
    String first = "/topic/channels/" + UUID.randomUUID();
    interceptor.preSend(subscribe(user, first), null);
    Message<byte[]> second = subscribe(user, "/topic/channels/" + UUID.randomUUID());
    assertThatThrownBy(() -> interceptor.preSend(second, null))
        .isInstanceOf(MessagingException.class)
        .hasMessage("DUPLICATE_SUBSCRIPTION_ID");
    assertThat(registry.byUser(user))
        .extracting(SessionSubscription::destination)
        .containsExactly(first);
  }

  // Dựng frame SUBSCRIBE id s1 của session sess-1 tới destination cho user
  private static Message<byte[]> subscribe(UUID user, String destination) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setSessionId("sess-1");
    accessor.setSubscriptionId("s1");
    accessor.setDestination(destination);
    accessor.setUser(new UsernamePasswordAuthenticationToken(user.toString(), null, List.of()));
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
