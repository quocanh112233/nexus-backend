package com.nexus.presence.application.relation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Kiểm tra áp event community vào projection quan hệ bằng projection giả
class ApplyRelationEventUseCaseTest {

  private final FakeRelationshipProjection projection = new FakeRelationshipProjection();
  private final ApplyRelationEventUseCase useCase = new ApplyRelationEventUseCase(projection);
  private final UUID user = UUID.randomUUID();
  private final UUID server = UUID.randomUUID();

  // Kịch bản: user đã loaded tham gia server
  // Input: MemberJoined
  // Output: serversOf(user) chứa server và membersOf(server) chứa user
  @Test
  void memberJoinedAddsServerForLoadedUser() {
    projection.load(user, Set.of());

    useCase.execute("MemberJoined", server, user);

    assertThat(projection.serversOf(user)).containsExactly(server);
    assertThat(projection.membersOf(server)).containsExactly(user);
  }

  // Kịch bản: user đã loaded rời, bị kick hoặc bị ban khỏi server
  // Input: MemberLeft, MemberKicked, MemberBanned
  // Output: serversOf(user) không còn server và membersOf(server) rỗng
  @ParameterizedTest
  @ValueSource(strings = {"MemberLeft", "MemberKicked", "MemberBanned"})
  void memberRemovalEventsRemoveServer(String eventType) {
    projection.load(user, Set.of(server));

    useCase.execute(eventType, server, user);

    assertThat(projection.serversOf(user)).isEmpty();
    assertThat(projection.membersOf(server)).isEmpty();
  }

  // Kịch bản: event đến user chưa loaded
  // Input: MemberJoined cho user chưa loaded
  // Output: serversOf(user) rỗng nhưng membersOf(server) vẫn chứa user
  @Test
  void eventForUnloadedUserIsIgnoredForUserSetButTracksServerMembers() {
    useCase.execute("MemberJoined", server, user);

    assertThat(projection.serversOf(user)).isEmpty();
    assertThat(projection.membersOf(server)).containsExactly(user);
  }

  // Kịch bản: xóa server có hai member đã loaded
  // Input: ServerDeleted
  // Output: cả hai user mất server, membersOf(server) rỗng, server khác còn nguyên
  @Test
  void serverDeletedRemovesServerFromEveryMember() {
    UUID other = UUID.randomUUID();
    UUID keep = UUID.randomUUID();
    projection.load(user, Set.of(server, keep));
    projection.load(other, Set.of(server));

    useCase.execute("ServerDeleted", server, null);

    assertThat(projection.serversOf(user)).containsExactly(keep);
    assertThat(projection.serversOf(other)).isEmpty();
    assertThat(projection.membersOf(server)).isEmpty();
  }

  // Kịch bản: cùng event giao hai lần
  // Input: MemberJoined hai lần rồi MemberLeft hai lần
  // Output: sau join tập có đúng 1 server, sau left tập rỗng, không lỗi
  @Test
  void duplicateEventIsIdempotent() {
    projection.load(user, Set.of());

    useCase.execute("MemberJoined", server, user);
    useCase.execute("MemberJoined", server, user);
    assertThat(projection.serversOf(user)).containsExactly(server);

    useCase.execute("MemberLeft", server, user);
    useCase.execute("MemberLeft", server, user);
    assertThat(projection.serversOf(user)).isEmpty();
  }

  // Kịch bản: event lạ
  // Input: eventType Whatever
  // Output: projection không đổi
  @Test
  void unknownEventTypeIsIgnored() {
    projection.load(user, Set.of());

    useCase.execute("Whatever", server, user);

    assertThat(projection.serversOf(user)).isEmpty();
    assertThat(projection.membersOf(server)).isEmpty();
  }
}
