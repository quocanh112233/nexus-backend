package com.nexus.community.application.server;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case xóa server, chỉ chủ server được phép, các bảng con xóa theo cascade
@Service
public class DeleteServerUseCase {

  private final ServerAccess access;
  private final ServerRepository serverRepository;
  private final DomainEventPublisher eventPublisher;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ và cổng phát sự kiện qua constructor
  public DeleteServerUseCase(
      ServerAccess access, ServerRepository serverRepository, DomainEventPublisher eventPublisher) {
    this.access = access;
    this.serverRepository = serverRepository;
    this.eventPublisher = eventPublisher;
  }

  // Phát ServerDeleted rồi xóa server trong cùng transaction
  @Transactional
  public void execute(ServerId serverId, UserId userId) {
    Server server = access.requireOwner(serverId, userId);
    server.delete();
    server.pullEvents().forEach(eventPublisher::publish);
    serverRepository.delete(serverId);
  }
}
