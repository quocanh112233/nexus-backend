package com.nexus.notification.application.messaging;

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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case áp event messaging lên activity, read state tác giả và mention, idempotent theo eventId
@Service
public class ApplyMessagingEventUseCase {

  private final EventInbox inbox;
  private final ChannelActivityRepository activity;
  private final MembershipRepository members;
  private final ReadStateRepository readStates;
  private final MentionRepository mentions;
  private final DomainEventPublisher publisher;

  // Nhận inbox của queue messaging, bốn repository projection và publisher outbox
  public ApplyMessagingEventUseCase(
      @Qualifier("messagingEventInbox") EventInbox inbox,
      ChannelActivityRepository activity,
      MembershipRepository members,
      ReadStateRepository readStates,
      MentionRepository mentions,
      DomainEventPublisher publisher) {
    this.inbox = inbox;
    this.activity = activity;
    this.members = members;
    this.readStates = readStates;
    this.mentions = mentions;
    this.publisher = publisher;
  }

  // Bỏ qua event trùng, áp MessageCreated hoặc DmChannelCreated, loại khác bỏ qua
  @Transactional
  public void execute(MessagingEvent event) {
    if (!inbox.firstDelivery(event.eventId())) {
      return;
    }
    switch (event.eventType()) {
      case "MessageCreated" -> onMessage(event);
      case "DmChannelCreated" -> {
        ChannelId channel = new ChannelId(event.channelId());
        List<UserId> users = event.participantIds().stream().map(UserId::new).toList();
        activity.upsertChannel(channel, null);
        members.addDmParticipants(channel, users.get(0), users.get(1));
      }
      default -> {}
    }
  }

  // Ghi tin rồi ghi read_states theo userId tăng dần để mọi transaction khóa dòng cùng thứ tự
  private void onMessage(MessagingEvent event) {
    ChannelId channel = new ChannelId(event.channelId());
    ServerId server = event.serverId() == null ? null : new ServerId(event.serverId());
    MessageId message = new MessageId(event.messageId());
    UserId author = new UserId(event.authorId());
    ChannelScope scope = new ChannelScope(channel, server);
    activity.recordMessage(channel, server, message, event.createdAt());
    Map<String, UserId> users = new TreeMap<>();
    if (members.canAccess(author, scope)) {
      users.put(author.value().toString(), author);
    }
    event.mentionUserIds().stream()
        .distinct()
        .map(UserId::new)
        .filter(user -> !user.equals(author) && canBeMentioned(user, scope))
        .forEach(user -> users.put(user.value().toString(), user));
    for (UserId user : users.values()) {
      if (user.equals(author)) {
        readStates.advance(author, channel, message);
      } else if (mentions.insertIfAbsent(
          message, user, channel, server, author, event.createdAt())) {
        int count = readStates.addMentionCount(user, channel, message);
        publisher.publish(new MentionCreated(user, channel, server, message, author, count));
      }
    }
  }

  // Member server hoặc participant DM; ponytail: mention rớt nếu MemberJoined chưa tới
  private boolean canBeMentioned(UserId user, ChannelScope scope) {
    return members.canAccess(user, scope);
  }
}
