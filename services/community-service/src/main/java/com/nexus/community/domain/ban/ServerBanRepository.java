package com.nexus.community.domain.ban;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import java.util.Optional;

// Cổng lưu trữ aggregate ServerBan
public interface ServerBanRepository {

  // Thêm ban nếu chưa có, true khi thực sự thêm hàng mới
  boolean addIfAbsent(ServerBan ban);

  // Tìm ban theo server và user
  Optional<ServerBan> find(ServerId serverId, UserId userId);

  // Gỡ ban, true khi thực sự xóa được một hàng
  boolean remove(ServerId serverId, UserId userId);

  // Lấy một trang ban theo user_id tăng dần, afterUserId null là trang đầu
  List<ServerBan> page(ServerId serverId, UserId afterUserId, int limit);
}
