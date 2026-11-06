package com.nexus.community.application.server;

import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn chi tiết một server mà user là thành viên
@Service
public class GetServerQuery {

  private final ServerAccess access;
  private final CategoryRepository categoryRepository;
  private final ChannelRepository channelRepository;

  // Nhận cổng kiểm tra quyền và các cổng lưu trữ qua constructor
  public GetServerQuery(
      ServerAccess access,
      CategoryRepository categoryRepository,
      ChannelRepository channelRepository) {
    this.access = access;
    this.categoryRepository = categoryRepository;
    this.channelRepository = channelRepository;
  }

  // Trả server kèm category, kênh theo vị trí rồi id và các role
  @Transactional(readOnly = true)
  public ServerDetail execute(ServerId serverId, UserId userId) {
    Server server = access.requireMember(serverId, userId);
    return new ServerDetail(
        ServerSummary.from(server),
        categoryRepository.findAllByServer(serverId).stream().map(CategoryView::from).toList(),
        channelRepository.findAllByServer(serverId).stream().map(ChannelView::from).toList(),
        server.roles().stream().map(RoleView::from).toList());
  }
}
