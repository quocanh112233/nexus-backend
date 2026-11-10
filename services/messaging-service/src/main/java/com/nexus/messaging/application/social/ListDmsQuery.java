package com.nexus.messaging.application.social;

import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.DmChannelRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn mọi DM của một user, kể cả khi đã hết bạn
@Service
public class ListDmsQuery {

  private final DmChannelRepository dmChannels;

  // Nhận repository kênh DM qua constructor
  public ListDmsQuery(DmChannelRepository dmChannels) {
    this.dmChannels = dmChannels;
  }

  // Trả các DM của user, mới nhất trước
  @Transactional(readOnly = true)
  public List<DmView> execute(UserId user) {
    return dmChannels.listOf(user).stream().map(c -> DmView.of(c, user)).toList();
  }
}
