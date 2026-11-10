package com.nexus.messaging.application.social;

import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.DmChannel;
import java.time.Instant;
import java.util.UUID;

// Góc nhìn một kênh DM từ phía một user: id kênh, người kia và thời điểm tạo
public record DmView(UUID channelId, UUID friendId, Instant createdAt) {

  // Dựng từ kênh DM theo góc nhìn của me
  static DmView of(DmChannel channel, UserId me) {
    return new DmView(channel.id(), channel.pair().other(me).value(), channel.createdAt());
  }
}
