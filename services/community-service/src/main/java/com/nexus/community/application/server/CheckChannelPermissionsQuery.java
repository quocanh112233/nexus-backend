package com.nexus.community.application.server;

import com.nexus.community.application.channel.ChannelNotFoundException;
import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.PermissionCalculator;
import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn quyền hiệu lực của user trên một kênh, không phải thành viên thì quyền rỗng
@Service
public class CheckChannelPermissionsQuery {

  private final ServerRepository serverRepository;
  private final MemberRepository memberRepository;
  private final ChannelRepository channelRepository;
  private final ChannelOverwriteRepository overwriteRepository;

  // Nhận các cổng lưu trữ qua constructor
  public CheckChannelPermissionsQuery(
      ServerRepository serverRepository,
      MemberRepository memberRepository,
      ChannelRepository channelRepository,
      ChannelOverwriteRepository overwriteRepository) {
    this.serverRepository = serverRepository;
    this.memberRepository = memberRepository;
    this.channelRepository = channelRepository;
    this.overwriteRepository = overwriteRepository;
  }

  // Tính quyền của user trên kênh, kênh không có thì ChannelNotFound, người ngoài server thì rỗng
  @Transactional(readOnly = true)
  public ChannelPermissionResult execute(UserId userId, ChannelId channelId) {
    Channel channel =
        channelRepository.findById(channelId).orElseThrow(ChannelNotFoundException::new);
    return memberRepository
        .find(channel.serverId(), userId)
        .map(
            member ->
                new ChannelPermissionResult(
                    channel.serverId(),
                    true,
                    PermissionCalculator.channel(
                        serverRepository.findById(channel.serverId()).orElseThrow(),
                        member,
                        overwriteRepository.findByChannel(channelId))))
        .orElseGet(
            () -> new ChannelPermissionResult(channel.serverId(), false, new PermissionSet(0)));
  }
}
