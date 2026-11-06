package com.nexus.community.domain.channel;

import com.nexus.community.domain.server.ServerId;
import java.util.List;
import java.util.Optional;

// Cổng lưu trữ aggregate Channel
public interface ChannelRepository {

  // Lưu kênh, version cũ thì ném ObjectOptimisticLockingFailureException
  void save(Channel channel);

  // Tìm kênh theo id
  Optional<Channel> findById(ChannelId id);

  // Liệt kê kênh của server, sắp theo vị trí rồi id
  List<Channel> findAllByServer(ServerId serverId);

  // Xóa kênh
  void delete(ChannelId id);
}
