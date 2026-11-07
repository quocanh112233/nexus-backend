package com.nexus.community.application.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.community.application.channel.ChannelNotFoundException;
import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelName;
import com.nexus.community.domain.channel.ChannelOverwrite;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.channel.OverwriteTarget;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.Position;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// Kiểm tra CheckChannelPermissionsQuery tính quyền hiệu lực của user trên kênh
class CheckChannelPermissionsQueryTest {

  private final ServerRepository servers = mock(ServerRepository.class);
  private final MemberRepository members = mock(MemberRepository.class);
  private final ChannelRepository channels = mock(ChannelRepository.class);
  private final ChannelOverwriteRepository overwrites = mock(ChannelOverwriteRepository.class);
  private final CheckChannelPermissionsQuery query =
      new CheckChannelPermissionsQuery(servers, members, channels, overwrites);
  private final UserId owner = UserId.newId();
  private final Server server = Server.create(new ServerName("S"), owner, Instant.now());
  private final Channel channel =
      Channel.create(server.id(), null, new ChannelName("general"), new Position(0), Instant.now());

  // Kịch bản: chủ server hỏi quyền trên kênh của mình
  // Input: execute với owner là thành viên, không có overwrite
  // Output: member true, serverId của kênh, permissions bằng ALL
  @Test
  void ownerHasAllPermissions() {
    stub(owner, List.of());

    ChannelPermissionResult result = query.execute(owner, channel.id());

    assertThat(result.member()).isTrue();
    assertThat(result.serverId()).isEqualTo(server.id());
    assertThat(result.permissions()).isEqualTo(PermissionSet.ALL);
  }

  // Kịch bản: member thường chưa có overwrite nào
  // Input: execute với member không phải chủ, overwrite rỗng
  // Output: permissions có xem và gửi tin, không có MANAGE_MESSAGES
  @Test
  void memberWithEveryoneDefaultsGetsViewAndSend() {
    UserId user = UserId.newId();
    stub(user, List.of());

    PermissionSet p = query.execute(user, channel.id()).permissions();

    assertThat(p.has(Permission.VIEW_CHANNEL)).isTrue();
    assertThat(p.has(Permission.SEND_MESSAGES)).isTrue();
    assertThat(p.has(Permission.MANAGE_MESSAGES)).isFalse();
  }

  // Kịch bản: overwrite của @everyone cấm gửi tin trên kênh
  // Input: member thường, overwrite ROLE @everyone deny SEND_MESSAGES
  // Output: còn VIEW_CHANNEL, mất SEND_MESSAGES
  @Test
  void overwriteDenySendRemovesSend() {
    UserId user = UserId.newId();
    stub(user, List.of(everyoneOverwrite(new PermissionSet(0).with(Permission.SEND_MESSAGES))));

    PermissionSet p = query.execute(user, channel.id()).permissions();

    assertThat(p.has(Permission.VIEW_CHANNEL)).isTrue();
    assertThat(p.has(Permission.SEND_MESSAGES)).isFalse();
  }

  // Kịch bản: overwrite của @everyone cấm xem kênh
  // Input: member thường, overwrite ROLE @everyone deny VIEW_CHANNEL
  // Output: member true, permissions rỗng
  @Test
  void denyViewYieldsEmptyPermissions() {
    UserId user = UserId.newId();
    stub(user, List.of(everyoneOverwrite(new PermissionSet(0).with(Permission.VIEW_CHANNEL))));

    ChannelPermissionResult result = query.execute(user, channel.id());

    assertThat(result.member()).isTrue();
    assertThat(result.permissions().bits()).isZero();
  }

  // Kịch bản: user không phải thành viên của server chứa kênh
  // Input: execute với user chưa tham gia
  // Output: member false, permissions rỗng, serverId vẫn là của kênh
  @Test
  void nonMemberReturnsMemberFalseAndEmpty() {
    UserId stranger = UserId.newId();
    when(channels.findById(channel.id())).thenReturn(Optional.of(channel));
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), stranger)).thenReturn(Optional.empty());

    ChannelPermissionResult result = query.execute(stranger, channel.id());

    assertThat(result.member()).isFalse();
    assertThat(result.permissions().bits()).isZero();
    assertThat(result.serverId()).isEqualTo(server.id());
  }

  // Kịch bản: kênh không tồn tại
  // Input: execute với channelId chưa có trong repository
  // Output: ném ChannelNotFoundException
  @Test
  void unknownChannelThrowsChannelNotFound() {
    when(channels.findById(channel.id())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> query.execute(owner, channel.id()))
        .isInstanceOf(ChannelNotFoundException.class);
  }

  // Cho kênh, server và tư cách thành viên của user cùng overwrite trả về từ repository
  private void stub(UserId user, List<ChannelOverwrite> list) {
    when(channels.findById(channel.id())).thenReturn(Optional.of(channel));
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), user))
        .thenReturn(Optional.of(Member.join(server.id(), user, Instant.now())));
    when(overwrites.findByChannel(channel.id())).thenReturn(list);
  }

  // Overwrite của role @everyone chỉ có phần deny
  private ChannelOverwrite everyoneOverwrite(PermissionSet deny) {
    return new ChannelOverwrite(
        OverwriteTarget.ROLE, server.defaultRole().id().value(), new PermissionSet(0), deny);
  }
}
