package com.nexus.identity.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nexus.common.messaging.OutboxMessage;
import com.nexus.common.messaging.OutboxWriter;
import com.nexus.identity.domain.user.AboutMe;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.UserProfileUpdated;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Kiểm tra OutboxDomainEventPublisher đổi sự kiện miền thành OutboxMessage đúng catalog
class OutboxDomainEventPublisherTest {

  private final OutboxWriter writer = mock(OutboxWriter.class);
  private final OutboxDomainEventPublisher publisher = new OutboxDomainEventPublisher(writer);

  // Kịch bản: UserProfileUpdated được ghi vào outbox với aggregate, routing key và payload đúng
  // Input: sự kiện của một user, displayName Alice, không avatar, aboutMe "hi"
  // Output: aggregate User, routing user.profile-updated, version 1, payload có 4 khóa đúng giá trị
  @Test
  void mapsUserProfileUpdatedToOutboxMessage() {
    UserId id = UserId.newId();

    publisher.publish(
        new UserProfileUpdated(id, new DisplayName("Alice"), null, new AboutMe("hi")));

    ArgumentCaptor<OutboxMessage> message = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(writer).append(message.capture());
    OutboxMessage m = message.getValue();
    assertThat(m.aggregateType()).isEqualTo("User");
    assertThat(m.aggregateId()).isEqualTo(id.value());
    assertThat(m.eventType()).isEqualTo("UserProfileUpdated");
    assertThat(m.eventVersion()).isEqualTo(1);
    assertThat(m.routingKey()).isEqualTo("user.profile-updated");
    assertThat(m.payload())
        .containsOnlyKeys("userId", "displayName", "avatarUrl", "aboutMe")
        .containsEntry("userId", id.value().toString())
        .containsEntry("displayName", "Alice")
        .containsEntry("avatarUrl", null)
        .containsEntry("aboutMe", "hi");
  }
}
