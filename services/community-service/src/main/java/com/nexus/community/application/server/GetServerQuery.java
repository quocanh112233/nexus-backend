package com.nexus.community.application.server;

import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.channel.ChannelOverwrite;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.PermissionCalculator;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn chi tiết một server mà user là thành viên
@Service
public class GetServerQuery {

  private final ServerAccess access;
  private final CategoryRepository categoryRepository;
  private final ChannelRepository channelRepository;
  private final ChannelOverwriteRepository overwriteRepository;

  // Nhận cổng kiểm tra quyền và các cổng lưu trữ qua constructor
  public GetServerQuery(
      ServerAccess access,
      CategoryRepository categoryRepository,
      ChannelRepository channelRepository,
      ChannelOverwriteRepository overwriteRepository) {
    this.access = access;
    this.categoryRepository = categoryRepository;
    this.channelRepository = channelRepository;
    this.overwriteRepository = overwriteRepository;
  }

  // Trả server kèm category, các kênh người gọi có VIEW_CHANNEL theo vị trí rồi id và các role
  @Transactional(readOnly = true)
  public ServerDetail execute(ServerId serverId, UserId userId) {
    Authorized auth = access.requireAuthorized(serverId, userId);
    Server server = auth.server();
    var overwrites = overwriteRepository.findByServer(serverId);
    return new ServerDetail(
        ServerSummary.from(server),
        categoryRepository.findAllByServer(serverId).stream().map(CategoryView::from).toList(),
        channelRepository.findAllByServer(serverId).stream()
            .filter(
                c ->
                    PermissionCalculator.channel(
                            server,
                            auth.member(),
                            overwrites.getOrDefault(c.id(), List.<ChannelOverwrite>of()))
                        .has(Permission.VIEW_CHANNEL))
            .map(ChannelView::from)
            .toList(),
        server.roles().stream().map(RoleView::from).toList());
  }
}
