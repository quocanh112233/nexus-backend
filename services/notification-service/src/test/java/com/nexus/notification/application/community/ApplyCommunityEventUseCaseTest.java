package com.nexus.notification.application.community;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.notification.application.port.EventInbox;
import com.nexus.notification.domain.readstate.ChannelActivityRepository;
import com.nexus.notification.domain.readstate.MembershipRepository;
import com.nexus.notification.domain.readstate.MentionRepository;
import com.nexus.notification.domain.readstate.ReadStateRepository;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

// Kiểm tra ApplyCommunityEventUseCase gọi đúng repository theo từng loại event community
class ApplyCommunityEventUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
  private static final Instant JOINED = Instant.parse("2026-11-09T08:00:00Z");

  private final UUID serverUuid = UUID.randomUUID();
  private final UUID channelUuid = UUID.randomUUID();
  private final UUID userUuid = UUID.randomUUID();
  private final ServerId server = new ServerId(serverUuid);
  private final ChannelId channel = new ChannelId(channelUuid);
  private final UserId user = new UserId(userUuid);
  private final EventInbox inbox = mock(EventInbox.class);
  private final ChannelActivityRepository activity = mock(ChannelActivityRepository.class);
  private final MembershipRepository members = mock(MembershipRepository.class);
  private final ReadStateRepository readStates = mock(ReadStateRepository.class);
  private final MentionRepository mentions = mock(MentionRepository.class);
  private final ApplyCommunityEventUseCase useCase =
      new ApplyCommunityEventUseCase(
          inbox, activity, members, readStates, mentions, Clock.fixed(NOW, ZoneOffset.UTC));

  // Kịch bản: event đã xử lý rồi thì không đụng repository nào
  // Input: inbox.firstDelivery trả false cho MemberJoined
  // Output: cả bốn repository không có tương tác nào
  @Test
  void duplicateEventIsNoOp() {
    when(inbox.firstDelivery(any())).thenReturn(false);

    useCase.execute(event("MemberJoined"));

    verifyNoInteractions(activity, members, readStates, mentions);
  }

  // Kịch bản: kênh mới tạo
  // Input: ChannelCreated lần đầu
  // Output: upsertChannel(channel, server) được gọi
  @Test
  void channelCreatedUpsertsActivity() {
    first();

    useCase.execute(event("ChannelCreated"));

    verify(activity).upsertChannel(channel, server);
  }

  // Kịch bản: thành viên vào server
  // Input: MemberJoined lần đầu có joinedAt
  // Output: addServerMember rồi baselineForJoin theo đúng thứ tự
  @Test
  void memberJoinedAddsMemberAndBaseline() {
    first();

    useCase.execute(event("MemberJoined"));

    InOrder order = inOrder(members, readStates);
    order.verify(members).addServerMember(server, user, JOINED);
    order.verify(readStates).baselineForJoin(server, user);
  }

  // Kịch bản: thành viên rời, bị kick hoặc bị ban
  // Input: MemberLeft, MemberKicked, MemberBanned lần đầu
  // Output: xóa thành viên, read state và mention của user trong server
  @ParameterizedTest
  @ValueSource(strings = {"MemberLeft", "MemberKicked", "MemberBanned"})
  void leftKickedBannedRemoveMemberAndState(String type) {
    first();

    useCase.execute(event(type));

    verify(members).removeServerMember(server, user);
    verify(readStates).deleteForUserInServer(user, server);
    verify(mentions).deleteForUserInServer(user, server);
  }

  // Kịch bản: kênh bị xóa
  // Input: ChannelDeleted lần đầu
  // Output: tombstoneChannel với serverId và thời điểm hiện tại
  @Test
  void channelDeletedTombstonesChannel() {
    first();

    useCase.execute(event("ChannelDeleted"));

    verify(activity).tombstoneChannel(channel, server, NOW);
  }

  // Kịch bản: server bị xóa
  // Input: ServerDeleted lần đầu
  // Output: tombstone server rồi dọn thành viên, read state, mention của server
  @Test
  void serverDeletedTombstonesAndPurges() {
    first();

    useCase.execute(event("ServerDeleted"));

    verify(activity).tombstoneServer(server, NOW);
    verify(members).removeServer(server);
    verify(readStates).deleteForServer(server);
    verify(mentions).deleteForServer(server);
  }

  // Kịch bản: loại event không hỗ trợ
  // Input: eventType Whatever lần đầu
  // Output: cả bốn repository không có tương tác nào
  @Test
  void unknownTypeIsIgnored() {
    first();

    useCase.execute(new CommunityEvent(UUID.randomUUID(), "Whatever", null, null, null, null));

    verifyNoInteractions(activity, members, readStates, mentions);
  }

  // Cho inbox coi mọi event là lần giao đầu tiên
  private void first() {
    when(inbox.firstDelivery(any())).thenReturn(true);
  }

  // Dựng event của loại type với đủ id của test
  private CommunityEvent event(String type) {
    return new CommunityEvent(UUID.randomUUID(), type, serverUuid, channelUuid, userUuid, JOINED);
  }
}
