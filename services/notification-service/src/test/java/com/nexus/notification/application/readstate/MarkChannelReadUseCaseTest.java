package com.nexus.notification.application.readstate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.notification.application.port.DomainEventPublisher;
import com.nexus.notification.application.shared.ChannelNotFoundException;
import com.nexus.notification.domain.readstate.ChannelActivityRepository;
import com.nexus.notification.domain.readstate.ChannelScope;
import com.nexus.notification.domain.readstate.MembershipRepository;
import com.nexus.notification.domain.readstate.ReadStateRepository;
import com.nexus.notification.domain.readstate.ReadStateSnapshot;
import com.nexus.notification.domain.readstate.ReadStateUpdated;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

// Kiểm tra MarkChannelReadUseCase: kênh lạ, không phải member và đánh dấu đã đọc có phát event
class MarkChannelReadUseCaseTest {

  private final UserId user = new UserId(UUID.randomUUID());
  private final ChannelId channel = new ChannelId(UUID.randomUUID());
  private final MessageId message = new MessageId(UUID.randomUUID());
  private final ChannelScope scope = new ChannelScope(channel, new ServerId(UUID.randomUUID()));
  private final ChannelActivityRepository activity = mock(ChannelActivityRepository.class);
  private final MembershipRepository members = mock(MembershipRepository.class);
  private final ReadStateRepository readStates = mock(ReadStateRepository.class);
  private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);
  private final MarkChannelReadUseCase useCase =
      new MarkChannelReadUseCase(activity, members, readStates, publisher);

  // Kịch bản: kênh không tồn tại hoặc đã xóa
  // Input: findLiveChannel rỗng
  // Output: ChannelNotFoundException, không đụng read state và publisher
  @Test
  void unknownChannelIsNotFound() {
    when(activity.findLiveChannel(channel)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.execute(user, channel, message))
        .isInstanceOf(ChannelNotFoundException.class);

    verifyNoInteractions(readStates, publisher);
  }

  // Kịch bản: kênh sống nhưng user không phải member (không lộ sự tồn tại kênh)
  // Input: findLiveChannel có kênh, canAccess false
  // Output: cùng ChannelNotFoundException và cùng thông điệp với kênh lạ, không ghi gì
  @Test
  void nonMemberIsNotFound() {
    when(activity.findLiveChannel(channel)).thenReturn(Optional.empty());
    String unknownMessage = thrownMessage();
    when(activity.findLiveChannel(channel)).thenReturn(Optional.of(scope));
    when(members.canAccess(user, scope)).thenReturn(false);

    assertThatThrownBy(() -> useCase.execute(user, channel, message))
        .isInstanceOf(ChannelNotFoundException.class)
        .hasMessage(unknownMessage);

    verifyNoInteractions(readStates, publisher);
  }

  // Kịch bản: member đánh dấu đã đọc
  // Input: kênh sống, canAccess true, advance trả snapshot (mốc m, 2 mention)
  // Output: trả snapshot; advance rồi publish ReadStateUpdated mang đúng giá trị snapshot
  @Test
  void memberAdvancesAndPublishes() {
    MessageId reached = new MessageId(UUID.randomUUID());
    ReadStateSnapshot snapshot = new ReadStateSnapshot(reached, 2);
    when(activity.findLiveChannel(channel)).thenReturn(Optional.of(scope));
    when(members.canAccess(user, scope)).thenReturn(true);
    when(readStates.advance(user, channel, message)).thenReturn(snapshot);

    ReadStateSnapshot result = useCase.execute(user, channel, message);

    assertThat(result).isEqualTo(snapshot);
    InOrder order = inOrder(readStates, publisher);
    order.verify(readStates).advance(user, channel, message);
    order.verify(publisher).publish(new ReadStateUpdated(user, channel, reached, 2));
  }

  // Lấy thông điệp ngoại lệ khi kênh không tồn tại
  private String thrownMessage() {
    try {
      useCase.execute(user, channel, message);
    } catch (ChannelNotFoundException e) {
      return e.getMessage();
    }
    throw new AssertionError("expected ChannelNotFoundException");
  }
}
