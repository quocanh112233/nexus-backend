package com.nexus.identity.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nexus.common.messaging.OutboxMessage;
import com.nexus.common.messaging.OutboxWriter;
import com.nexus.identity.domain.shared.DomainEvent;
import com.nexus.identity.domain.user.AboutMe;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.UserProfileUpdated;
import com.nexus.identity.domain.user.UserRegistered;
import com.nexus.identity.domain.user.Username;
import com.nexus.testsupport.ContractFixtures;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

// Kiểm tra payload identity.events khớp fixture trong contracts/events
class OutboxEventContractTest {

  // Các event identity: tên, routing key và event miền mẫu
  static Stream<Arguments> events() {
    UserId id = UserId.newId();
    return Stream.of(
        Arguments.of(
            "UserRegistered",
            "user.registered",
            new UserRegistered(id, new Username("alice"), new DisplayName("Alice"))),
        Arguments.of(
            "UserProfileUpdated",
            "user.profile-updated",
            new UserProfileUpdated(
                id, new DisplayName("Alice"), "https://x/a.png", new AboutMe("hi"))));
  }

  // Kịch bản: payload publisher phát ra cùng khóa và kiểu với fixture của event đó
  // Input: event miền mẫu theo eventType đi qua OutboxDomainEventPublisher
  // Output: routing key đúng catalog và assertSameShape không lỗi
  @ParameterizedTest
  @MethodSource("events")
  void payloadMatchesContract(String eventType, String routingKey, DomainEvent event) {
    OutboxWriter writer = mock(OutboxWriter.class);

    new OutboxDomainEventPublisher(writer).publish(event);

    ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(writer).append(captor.capture());
    assertThat(captor.getValue().eventType()).isEqualTo(eventType);
    assertThat(captor.getValue().routingKey()).isEqualTo(routingKey);
    ContractFixtures.assertSameShape(captor.getValue().payload(), eventType);
  }
}
