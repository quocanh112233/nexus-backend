package com.nexus.community.application.server;

import com.nexus.community.domain.channel.ChannelOverwrite;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.server.PermissionCalculator;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn quyền hiệu lực của thành viên gọi trong server và trên từng kênh
@Service
public class MyPermissionsQuery {

  private final ServerAccess access;
  private final ChannelRepository channelRepository;
  private final ChannelOverwriteRepository overwriteRepository;

  // Nhận cổng kiểm tra quyền và các cổng lưu trữ qua constructor
  public MyPermissionsQuery(
      ServerAccess access,
      ChannelRepository channelRepository,
      ChannelOverwriteRepository overwriteRepository) {
    this.access = access;
    this.channelRepository = channelRepository;
    this.overwriteRepository = overwriteRepository;
  }

  // Tính quyền server rồi quyền từng kênh nhìn thấy được, overwrite cả server nạp bằng một query
  @Transactional(readOnly = true)
  public MyPermissions execute(ServerId serverId, UserId userId) {
    Authorized auth = access.requireAuthorized(serverId, userId);
    var overwrites = overwriteRepository.findByServer(serverId);
    List<MyPermissions.ChannelPermissions> channels =
        channelRepository.findAllByServer(serverId).stream()
            .map(
                c ->
                    new MyPermissions.ChannelPermissions(
                        c.id().value(),
                        PermissionCalculator.channel(
                                auth.server(),
                                auth.member(),
                                overwrites.getOrDefault(c.id(), List.<ChannelOverwrite>of()))
                            .bits()))
            .filter(c -> c.permissions() != 0)
            .toList();
    return new MyPermissions(
        PermissionCalculator.server(auth.server(), auth.member()).bits(), channels);
  }
}
