package com.nexus.community.application.server;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case đổi tên server, cần quyền MANAGE_SERVER
@Service
public class RenameServerUseCase {

  private final ServerAccess access;
  private final ServerRepository serverRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public RenameServerUseCase(
      ServerAccess access,
      ServerRepository serverRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.access = access;
    this.serverRepository = serverRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Đổi tên, lưu rồi phát ServerUpdated, trả tóm tắt mới
  @Transactional
  public ServerSummary execute(ServerId serverId, UserId userId, String name) {
    Server server = access.requirePermission(serverId, userId, Permission.MANAGE_SERVER).server();
    server.rename(new ServerName(name), clock.instant().truncatedTo(ChronoUnit.MICROS));
    serverRepository.save(server);
    server.pullEvents().forEach(eventPublisher::publish);
    return ServerSummary.from(server);
  }
}
