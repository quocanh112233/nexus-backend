package com.nexus.messaging.application.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.shared.FriendNotFoundException;
import com.nexus.messaging.application.social.SocialFakes.FakeFriendships;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendRemoved;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra xóa bạn: có quan hệ thì xóa và phát event, không có thì 404
class RemoveFriendUseCaseTest {

  private final UserId me = new UserId(UUID.randomUUID());
  private final UserId friend = new UserId(UUID.randomUUID());
  private final FakeFriendships friendships = new FakeFriendships();
  private final List<DomainEvent> events = SocialFakes.eventSink();
  private final RemoveFriendUseCase useCase = new RemoveFriendUseCase(friendships, events::add);

  // Kịch bản: xóa một người bạn đang có
  // Input: execute(me, friend) khi đã là bạn
  // Output: friendship bị xóa, phát FriendRemoved(me, friend)
  @Test
  void removesFriendAndEmitsEvent() {
    friendships.insertIfAbsent(UserPair.of(me, friend), Instant.now());

    useCase.execute(me, friend);

    assertThat(friendships.pairs).isEmpty();
    assertThat(events).containsExactly(new FriendRemoved(me, friend));
  }

  // Kịch bản: xóa người không phải bạn
  // Input: execute(me, friend) khi chưa là bạn
  // Output: FriendNotFoundException, không event
  @Test
  void notFriendsIsNotFound() {
    assertThatThrownBy(() -> useCase.execute(me, friend))
        .isInstanceOf(FriendNotFoundException.class);

    assertThat(events).isEmpty();
  }
}
