package com.nexus.messaging.domain.social;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import java.util.List;
import java.util.Optional;

// Cổng lưu trữ kênh DM, DB bảo đảm mỗi cặp chỉ một kênh
public interface DmChannelRepository {

  // Tìm kênh DM của cặp
  Optional<DmChannel> findByPair(UserPair pair);

  // Tìm kênh DM theo id
  Optional<DmChannel> findById(ChannelId id);

  // Thêm kênh nếu cặp chưa có kênh, true khi chèn thật
  boolean insertIfAbsent(DmChannel channel);

  // Các kênh DM có user tham gia, mới nhất trước
  List<DmChannel> listOf(UserId user);
}
