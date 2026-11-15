package com.nexus.notification.application.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.notification.application.port.BackfillSourceUnavailableException;
import com.nexus.notification.application.port.BackfillState;
import com.nexus.notification.application.port.MembershipSnapshotSource;
import com.nexus.notification.application.port.MembershipSnapshotSource.DmSnapshot;
import com.nexus.notification.application.port.MembershipSnapshotSource.MemberSnapshot;
import com.nexus.notification.domain.readstate.ChannelActivityRepository;
import com.nexus.notification.domain.readstate.MembershipRepository;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

// Kiểm tra BackfillMembershipUseCase nạp snapshot một lần rồi đánh dấu membership-v1
class BackfillMembershipUseCaseTest {

  private static final Instant JOINED = Instant.parse("2026-01-02T03:04:05Z");

  private final ServerId server = new ServerId(UUID.randomUUID());
  private final UserId a = new UserId(UUID.randomUUID());
  private final UserId b = new UserId(UUID.randomUUID());
  private final ChannelId dm = new ChannelId(UUID.randomUUID());
  private final MembershipSnapshotSource source = mock(MembershipSnapshotSource.class);
  private final MembershipRepository members = mock(MembershipRepository.class);
  private final ChannelActivityRepository activity = mock(ChannelActivityRepository.class);
  private final BackfillState state = mock(BackfillState.class);
  private final BackfillMembershipUseCase useCase =
      new BackfillMembershipUseCase(source, members, activity, state);

  // Kịch bản: chưa từng backfill, snapshot có 2 member và 1 DM
  // Input: isDone false, source trả member a, b của server và DM (a, b)
  // Output: trả true, ghi đủ member kèm joinedAt và DM, markDone membership-v1 sau cùng
  @Test
  void loadsMembersAndDmsThenMarksDone() {
    feed(
        List.of(new MemberSnapshot(server, a, JOINED), new MemberSnapshot(server, b, JOINED)),
        List.of(new DmSnapshot(dm, a, b)));

    assertThat(useCase.execute()).isTrue();

    InOrder order = inOrder(members, state);
    order.verify(members).addServerMember(server, a, JOINED);
    order.verify(members).addServerMember(server, b, JOINED);
    order.verify(members).addDmParticipants(dm, a, b);
    order.verify(state).markDone("membership-v1");
  }

  // Kịch bản: đã có dòng đánh dấu membership-v1
  // Input: isDone("membership-v1") trả true
  // Output: trả false, không gọi source, không ghi gì, không markDone
  @Test
  void secondRunIsNoOp() {
    when(state.isDone("membership-v1")).thenReturn(true);

    assertThat(useCase.execute()).isFalse();

    verifyNoInteractions(source, members, activity);
    verify(state, never()).markDone(anyString());
  }

  // Kịch bản: nguồn snapshot lỗi giữa chừng
  // Input: forEachMember ném BackfillSourceUnavailableException
  // Output: ngoại lệ lan ra và không markDone
  @Test
  void sourceFailureLeavesNotDone() {
    doThrow(new BackfillSourceUnavailableException("down", null)).when(source).forEachMember(any());

    assertThatThrownBy(useCase::execute).isInstanceOf(BackfillSourceUnavailableException.class);

    verify(state, never()).markDone(anyString());
  }

  // Kịch bản: snapshot có một kênh DM
  // Input: source trả DM (a, b), không có member
  // Output: upsertChannel(dm, null) và addDmParticipants(dm, a, b)
  @Test
  void dmSnapshotRegistersChannelAndParticipants() {
    feed(List.of(), List.of(new DmSnapshot(dm, a, b)));

    useCase.execute();

    verify(activity).upsertChannel(dm, null);
    verify(members).addDmParticipants(dm, a, b);
  }

  // Cho source đẩy danh sách member và DM cho trước vào sink
  private void feed(List<MemberSnapshot> memberRows, List<DmSnapshot> dmRows) {
    doAnswer(
            inv -> {
              memberRows.forEach(inv.<Consumer<MemberSnapshot>>getArgument(0));
              return null;
            })
        .when(source)
        .forEachMember(any());
    doAnswer(
            inv -> {
              dmRows.forEach(inv.<Consumer<DmSnapshot>>getArgument(0));
              return null;
            })
        .when(source)
        .forEachDm(any());
  }
}
