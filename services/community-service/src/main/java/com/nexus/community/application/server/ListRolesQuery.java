package com.nexus.community.application.server;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn role của server cho mọi thành viên
@Service
public class ListRolesQuery {

  private final ServerAccess access;

  // Nhận cổng kiểm tra quyền qua constructor
  public ListRolesQuery(ServerAccess access) {
    this.access = access;
  }

  // Trả role theo vị trí tăng dần
  @Transactional(readOnly = true)
  public List<RoleView> execute(ServerId serverId, UserId userId) {
    return access.requireMember(serverId, userId).roles().stream()
        .sorted(Comparator.comparingInt(r -> r.position()))
        .map(RoleView::from)
        .toList();
  }
}
