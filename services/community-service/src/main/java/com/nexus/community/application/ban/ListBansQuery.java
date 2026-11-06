package com.nexus.community.application.ban;

import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.ban.ServerBan;
import com.nexus.community.domain.ban.ServerBanRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn trang ban của server theo keyset, cần BAN_MEMBERS
@Service
public class ListBansQuery {

  private final ServerAccess access;
  private final ServerBanRepository banRepository;

  // Nhận cổng kiểm tra quyền và cổng lưu trữ qua constructor
  public ListBansQuery(ServerAccess access, ServerBanRepository banRepository) {
    this.access = access;
    this.banRepository = banRepository;
  }

  // Lấy limit+1 dòng sau afterUserId để biết còn trang sau, cắt về limit
  @Transactional(readOnly = true)
  public BanPage execute(ServerId serverId, UserId caller, UserId afterUserId, int limit) {
    access.requirePermission(serverId, caller, Permission.BAN_MEMBERS);
    List<ServerBan> rows = banRepository.page(serverId, afterUserId, limit + 1);
    boolean hasMore = rows.size() > limit;
    List<BanView> items = rows.stream().limit(limit).map(BanView::from).toList();
    return new BanPage(items, hasMore ? items.get(items.size() - 1).userId() : null);
  }
}
