package com.nexus.notification.application.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.notification.application.port.DomainEventPublisher;
import com.nexus.notification.application.port.EventInbox;
import com.nexus.notification.domain.readstate.ChannelActivityRepository;
import com.nexus.notification.domain.readstate.ChannelScope;
import com.nexus.notification.domain.readstate.MembershipRepository;
import com.nexus.notification.domain.readstate.MentionCreated;
import com.nexus.notification.domain.readstate.MentionRepository;
import com.nexus.notification.domain.readstate.ReadStateRepository;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

// Kiểm tra ApplyMessagingEventUseCase: activity, tác giả tự đọc, mention hợp lệ và thứ tự khóa
class ApplyMessagingEventUseCaseTest {

  private static final Instant AT = Instant.parse("2026-11-10T10:00:00Z");

  private final UUID serverUuid = UUID.randomUUID();
  private final UUID channelUuid = UUID.randomUUID();
  private final UUID messageUuid = UUID.randomUUID();
  private final UUID authorUuid = UUID.fromString("50000000-0000-0000-0000-000000000000");
  private final ServerId server = new ServerId(serverUuid);
  private final ChannelId channel = new ChannelId(channelUuid);
  private final MessageId message = new MessageId(messageUuid);
  private final UserId author = new UserId(authorUuid);
  private final ChannelScope scope = new ChannelScope(channel, server);
  private final EventInbox inbox = mock(EventInbox.class);
  private final ChannelActivityRepository activity = mock(ChannelActivityRepository.class);
  private final MembershipRepository members = mock(MembershipRepository.class);
  private final ReadStateRepository readStates = mock(ReadStateRepository.class);
  private final MentionRepository mentions = mock(MentionRepository.class);
  private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);
  private final ApplyMessagingEventUseCase useCase =
      new ApplyMessagingEventUseCase(inbox, activity, members, readStates, mentions, publisher);

  // Kịch bản: event đã xử lý rồi thì không đụng repository nào
  // Input: inbox.firstDelivery trả false cho MessageCreated
  // Output: repository và publisher không có tương tác nào
  @Test
  void duplicateEventIsNoOp() {
    when(inbox.firstDelivery(any())).thenReturn(false);

    useCase.execute(message(serverUuid, List.of(UUID.randomUUID())));

    verifyNoInteractions(activity, members, readStates, mentions, publisher);
  }

  // Kịch bản: tác giả là member gửi tin (D2)
  // Input: MessageCreated lần đầu, canAccess(author) true, không mention
  // Output: recordMessage rồi advance(author, channel, message) theo đúng thứ tự
  @Test
  void messageRecordsActivityAndAuthorReadsOwnMessage() {
    first();
    when(members.canAccess(author, scope)).thenReturn(true);

    useCase.execute(message(serverUuid, List.of()));

    InOrder order = inOrder(activity, readStates);
    order.verify(activity).recordMessage(channel, server, message, AT);
    order.verify(readStates).advance(author, channel, message);
  }

  // Kịch bản: tác giả không còn là member
  // Input: MessageCreated lần đầu, canAccess(author) false
  // Output: recordMessage có gọi, advance không gọi
  @Test
  void authorNotMemberSkipsSelfRead() {
    first();

    useCase.execute(message(serverUuid, List.of()));

    verify(activity).recordMessage(channel, server, message, AT);
    verify(readStates, never()).advance(any(), any(), any());
  }

  // Kịch bản: mention member hợp lệ lần đầu
  // Input: mention user có wasMemberAt true, insertIfAbsent true, addMentionCount trả 3
  // Output: insertIfAbsent → addMentionCount → publish MentionCreated mentionCount 3
  @Test
  void validMentionIncrementsAndPublishes() {
    first();
    UserId target = new UserId(UUID.randomUUID());
    when(members.wasMemberAt(target, scope, AT)).thenReturn(true);
    when(mentions.insertIfAbsent(message, target, channel, server, author, AT)).thenReturn(true);
    when(readStates.addMentionCount(target, channel, message)).thenReturn(3);

    useCase.execute(message(serverUuid, List.of(target.value())));

    InOrder order = inOrder(mentions, readStates, publisher);
    order.verify(mentions).insertIfAbsent(message, target, channel, server, author, AT);
    order.verify(readStates).addMentionCount(target, channel, message);
    order
        .verify(publisher)
        .publish(new MentionCreated(target, channel, server, message, author, 3));
  }

  // Kịch bản: mention người ngoài server
  // Input: mention user có wasMemberAt false
  // Output: không insertIfAbsent, không addMentionCount, không publish
  @Test
  void mentionOfOutsiderIgnored() {
    first();

    useCase.execute(message(serverUuid, List.of(UUID.randomUUID())));

    verifyNoInteractions(mentions, publisher);
    verify(readStates, never()).addMentionCount(any(), any(), any());
  }

  // Kịch bản: tin gửi trước lúc user vào server đến muộn
  // Input: mention user có wasMemberAt(createdAt của tin) false
  // Output: không insertIfAbsent, không publish, vẫn recordMessage
  @Test
  void preJoinMentionIsIgnored() {
    first();
    UserId target = new UserId(UUID.randomUUID());
    when(members.wasMemberAt(target, scope, AT)).thenReturn(false);

    useCase.execute(message(serverUuid, List.of(target.value())));

    verify(members).wasMemberAt(target, scope, AT);
    verify(activity).recordMessage(channel, server, message, AT);
    verifyNoInteractions(mentions, publisher);
    verify(readStates, never()).addMentionCount(any(), any(), any());
  }

  // Kịch bản: tin gửi sau lúc user vào server
  // Input: mention user có wasMemberAt(createdAt của tin) true, insertIfAbsent true
  // Output: insertIfAbsent và publish MentionCreated với mentionCount 1
  @Test
  void postJoinMentionCounts() {
    first();
    UserId target = new UserId(UUID.randomUUID());
    when(members.wasMemberAt(target, scope, AT)).thenReturn(true);
    when(mentions.insertIfAbsent(message, target, channel, server, author, AT)).thenReturn(true);
    when(readStates.addMentionCount(target, channel, message)).thenReturn(1);

    useCase.execute(message(serverUuid, List.of(target.value())));

    verify(publisher).publish(new MentionCreated(target, channel, server, message, author, 1));
  }

  // Kịch bản: tác giả tự mention mình
  // Input: mentionUserIds chứa authorId, tác giả là member
  // Output: chỉ advance của tác giả, không insert mention, không publish
  @Test
  void selfMentionIgnored() {
    first();
    when(members.canAccess(author, scope)).thenReturn(true);

    useCase.execute(message(serverUuid, List.of(authorUuid)));

    verify(readStates).advance(author, channel, message);
    verifyNoInteractions(mentions, publisher);
  }

  // Kịch bản: mention đã có từ event khác cùng messageId
  // Input: mention user có wasMemberAt true, insertIfAbsent false
  // Output: không addMentionCount, không publish
  @Test
  void duplicateMentionDoesNotPublish() {
    first();
    UserId target = new UserId(UUID.randomUUID());
    when(members.wasMemberAt(target, scope, AT)).thenReturn(true);

    useCase.execute(message(serverUuid, List.of(target.value())));

    verify(mentions).insertIfAbsent(message, target, channel, server, author, AT);
    verify(readStates, never()).addMentionCount(any(), any(), any());
    verifyNoInteractions(publisher);
  }

  // Kịch bản: tin DM mention participant và một người ngoài (FR-093)
  // Input: serverId null, mention participant (wasMemberAt DM true) và người ngoài
  // Output: chỉ participant được insert, publish MentionCreated serverId null
  @Test
  void dmMessageMentionsParticipantOnly() {
    first();
    ChannelScope dm = new ChannelScope(channel, null);
    UserId peer = new UserId(UUID.randomUUID());
    UserId outsider = new UserId(UUID.randomUUID());
    when(members.wasMemberAt(peer, dm, AT)).thenReturn(true);
    when(mentions.insertIfAbsent(message, peer, channel, null, author, AT)).thenReturn(true);
    when(readStates.addMentionCount(peer, channel, message)).thenReturn(1);

    useCase.execute(message(null, List.of(peer.value(), outsider.value())));

    verify(activity).recordMessage(channel, null, message, AT);
    verify(publisher).publish(new MentionCreated(peer, channel, null, message, author, 1));
    verify(mentions, never()).insertIfAbsent(any(), eq(outsider), any(), any(), any(), any());
  }

  // Kịch bản: kênh DM mới tạo
  // Input: DmChannelCreated lần đầu có hai participantIds
  // Output: upsertChannel(channel, null) và addDmParticipants với hai user theo thứ tự payload
  @Test
  void dmChannelCreatedRegistersActivityAndParticipants() {
    first();
    UUID low = UUID.randomUUID();
    UUID high = UUID.randomUUID();

    useCase.execute(
        new MessagingEvent(
            UUID.randomUUID(),
            "DmChannelCreated",
            null,
            channelUuid,
            null,
            null,
            List.of(),
            null,
            List.of(low, high)));

    verify(activity).upsertChannel(channel, null);
    verify(members).addDmParticipants(channel, new UserId(low), new UserId(high));
  }

  // Kịch bản: tác giả nằm giữa hai người được mention theo thứ tự userId
  // Input: mention 9…, 1… (đảo thứ tự), tác giả 5…, cả ba đều truy cập được kênh
  // Output: thao tác read_states gọi theo 1… (mention), 5… (advance), 9… (mention)
  @Test
  void writesReadStatesInUserIdOrder() {
    first();
    UserId low = new UserId(UUID.fromString("10000000-0000-0000-0000-000000000000"));
    UserId high = new UserId(UUID.fromString("90000000-0000-0000-0000-000000000000"));
    when(members.canAccess(any(), eq(scope))).thenReturn(true);
    when(members.wasMemberAt(any(), eq(scope), eq(AT))).thenReturn(true);
    when(mentions.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(true);

    useCase.execute(message(serverUuid, List.of(high.value(), low.value())));

    InOrder order = inOrder(readStates);
    order.verify(readStates).addMentionCount(low, channel, message);
    order.verify(readStates).advance(author, channel, message);
    order.verify(readStates).addMentionCount(high, channel, message);
  }

  // Inbox nhận event lần đầu
  private void first() {
    when(inbox.firstDelivery(any())).thenReturn(true);
  }

  // MessageCreated của tác giả cố định với serverId và danh sách mention cho trước
  private MessagingEvent message(UUID serverId, List<UUID> mentionUserIds) {
    return new MessagingEvent(
        UUID.randomUUID(),
        "MessageCreated",
        messageUuid,
        channelUuid,
        serverId,
        authorUuid,
        mentionUserIds,
        AT,
        List.of());
  }
}
