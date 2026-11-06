package com.nexus.community.application.invite;

import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.invite.InviteRepository;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn lời mời của server, chỉ chủ server được phép
@Service
public class ListInvitesQuery {

  private final ServerAccess access;
  private final InviteRepository inviteRepository;

  // Nhận cổng kiểm tra quyền và cổng lưu trữ qua constructor
  public ListInvitesQuery(ServerAccess access, InviteRepository inviteRepository) {
    this.access = access;
    this.inviteRepository = inviteRepository;
  }

  // Liệt kê lời mời của server, mới tạo trước
  @Transactional(readOnly = true)
  public List<InviteView> execute(ServerId serverId, UserId userId) {
    access.requireOwner(serverId, userId);
    return inviteRepository.findAllByServer(serverId).stream().map(InviteView::from).toList();
  }
}
