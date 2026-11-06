package com.nexus.community.application.server;

import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn các server mà user là thành viên
@Service
public class ListMyServersQuery {

  private final ServerRepository serverRepository;

  // Nhận cổng lưu trữ server qua constructor
  public ListMyServersQuery(ServerRepository serverRepository) {
    this.serverRepository = serverRepository;
  }

  // Liệt kê tóm tắt server của user theo thứ tự tạo
  @Transactional(readOnly = true)
  public List<ServerSummary> execute(UserId userId) {
    return serverRepository.findAllByMember(userId).stream().map(ServerSummary::from).toList();
  }
}
