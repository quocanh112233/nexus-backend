package com.nexus.notification.application.port;

import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.time.Instant;
import java.util.function.Consumer;

// Cổng đọc snapshot toàn bộ member server và kênh DM từ service nguồn, đẩy từng bản ghi vào sink
public interface MembershipSnapshotSource {

  // Đẩy mọi member server vào sink, ném BackfillSourceUnavailableException nếu nguồn lỗi
  void forEachMember(Consumer<MemberSnapshot> sink);

  // Đẩy mọi kênh DM vào sink, ném BackfillSourceUnavailableException nếu nguồn lỗi
  void forEachDm(Consumer<DmSnapshot> sink);

  // Một member của server kèm thời điểm tham gia thật từ Community
  record MemberSnapshot(ServerId serverId, UserId userId, Instant joinedAt) {}

  // Một kênh DM cùng hai participant
  record DmSnapshot(ChannelId channelId, UserId low, UserId high) {}
}
