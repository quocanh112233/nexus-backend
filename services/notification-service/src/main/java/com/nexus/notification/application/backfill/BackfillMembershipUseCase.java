package com.nexus.notification.application.backfill;

import com.nexus.notification.application.port.BackfillState;
import com.nexus.notification.application.port.MembershipSnapshotSource;
import com.nexus.notification.domain.readstate.ChannelActivityRepository;
import com.nexus.notification.domain.readstate.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case nạp membership ban đầu từ snapshot đúng một lần, đánh dấu membership-v1 khi xong
@Service
public class BackfillMembershipUseCase {

  static final String NAME = "membership-v1";

  private final MembershipSnapshotSource source;
  private final MembershipRepository members;
  private final ChannelActivityRepository activity;
  private final BackfillState state;

  // Nhận nguồn snapshot, hai repository projection và kho đánh dấu
  public BackfillMembershipUseCase(
      MembershipSnapshotSource source,
      MembershipRepository members,
      ChannelActivityRepository activity,
      BackfillState state) {
    this.source = source;
    this.members = members;
    this.activity = activity;
    this.state = state;
  }

  // Trả false nếu đã nạp; ponytail: một transaction cả lần nạp, chia lô commit khi dữ liệu quá lớn
  @Transactional
  public boolean execute() {
    if (state.isDone(NAME)) {
      return false;
    }
    source.forEachMember(m -> members.addServerMember(m.serverId(), m.userId(), m.joinedAt()));
    source.forEachDm(
        dm -> {
          activity.upsertChannel(dm.channelId(), null);
          members.addDmParticipants(dm.channelId(), dm.low(), dm.high());
        });
    state.markDone(NAME);
    return true;
  }
}
