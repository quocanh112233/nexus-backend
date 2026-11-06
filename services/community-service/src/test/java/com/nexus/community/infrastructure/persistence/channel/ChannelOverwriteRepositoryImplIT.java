package com.nexus.community.infrastructure.persistence.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelName;
import com.nexus.community.domain.channel.ChannelOverwrite;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.channel.OverwriteTarget;
import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.Position;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra ChannelOverwriteRepositoryImpl với Postgres thật
@SpringBootTest
class ChannelOverwriteRepositoryImplIT extends CommunityIntegrationTest {

  @Autowired private ChannelOverwriteRepository repository;
  @Autowired private ChannelRepository channels;
  @Autowired private ServerRepository servers;
  @Autowired private TransactionTemplate tx;

  // Kịch bản: upsert hai lần cùng đích rồi một đích khác
  // Input: ROLE r allow 64 rồi ROLE r deny 64, rồi MEMBER cùng id r allow 32
  // Output: hàng ROLE r mang giá trị sau cùng; MEMBER là hàng riêng; findByChannel trả hai hàng
  @Test
  void upsertOverwritesSameTarget() {
    Server server = savedServer();
    Channel channel = savedChannel(server, "a");
    UUID target = UUID.randomUUID();

    upsert(channel, OverwriteTarget.ROLE, target, 64, 0);
    upsert(channel, OverwriteTarget.ROLE, target, 0, 64);
    upsert(channel, OverwriteTarget.MEMBER, target, 32, 0);

    assertThat(repository.findByChannel(channel.id()))
        .containsExactlyInAnyOrder(
            new ChannelOverwrite(
                OverwriteTarget.ROLE, target, new PermissionSet(0), new PermissionSet(64)),
            new ChannelOverwrite(
                OverwriteTarget.MEMBER, target, new PermissionSet(32), new PermissionSet(0)));
  }

  // Kịch bản: gom overwrite của cả server bằng một lần gọi
  // Input: hai kênh của server A có overwrite, một kênh của server B có overwrite, một kênh trống
  // Output: map của A có đúng hai khóa kênh có overwrite với số hàng đúng, không lẫn server B
  @Test
  void findByServerGroupsByChannel() {
    Server a = savedServer();
    Server b = savedServer();
    Channel a1 = savedChannel(a, "a1");
    Channel a2 = savedChannel(a, "a2");
    savedChannel(a, "empty");
    Channel b1 = savedChannel(b, "b1");
    upsert(a1, OverwriteTarget.ROLE, UUID.randomUUID(), 64, 0);
    upsert(a1, OverwriteTarget.MEMBER, UUID.randomUUID(), 0, 32);
    upsert(a2, OverwriteTarget.ROLE, UUID.randomUUID(), 0, 64);
    upsert(b1, OverwriteTarget.ROLE, UUID.randomUUID(), 64, 0);

    Map<ChannelId, List<ChannelOverwrite>> byChannel = repository.findByServer(a.id());

    assertThat(byChannel).containsOnlyKeys(a1.id(), a2.id());
    assertThat(byChannel.get(a1.id())).hasSize(2);
    assertThat(byChannel.get(a2.id())).hasSize(1);
    assertThat(repository.findByServer(b.id())).containsOnlyKeys(b1.id());
  }

  // Kịch bản: gỡ một overwrite theo khóa
  // Input: remove đích có thật, remove lại, remove đích sai loại
  // Output: true, false, false; hàng còn lại không bị đụng
  @Test
  void removeReportsWhetherRowExisted() {
    Server server = savedServer();
    Channel channel = savedChannel(server, "a");
    UUID target = UUID.randomUUID();
    upsert(channel, OverwriteTarget.ROLE, target, 64, 0);

    assertThat(remove(channel, OverwriteTarget.MEMBER, target)).isFalse();
    assertThat(remove(channel, OverwriteTarget.ROLE, target)).isTrue();
    assertThat(remove(channel, OverwriteTarget.ROLE, target)).isFalse();

    assertThat(repository.findByChannel(channel.id())).isEmpty();
  }

  // Kịch bản: xóa mọi overwrite của một đích trong server
  // Input: đích x có overwrite ở hai kênh server A và một kênh server B, đích y ở kênh A
  // Output: removeByTarget(A, x) xóa hai hàng của A; hàng của y và hàng của x ở server B còn
  @Test
  void removeByTargetClearsOnlyThatServer() {
    Server a = savedServer();
    Server b = savedServer();
    Channel a1 = savedChannel(a, "a1");
    Channel a2 = savedChannel(a, "a2");
    Channel b1 = savedChannel(b, "b1");
    UUID x = UUID.randomUUID();
    UUID y = UUID.randomUUID();
    upsert(a1, OverwriteTarget.ROLE, x, 64, 0);
    upsert(a2, OverwriteTarget.MEMBER, x, 0, 64);
    upsert(a1, OverwriteTarget.ROLE, y, 64, 0);
    upsert(b1, OverwriteTarget.ROLE, x, 64, 0);

    tx.executeWithoutResult(s -> repository.removeByTarget(a.id(), x));

    assertThat(repository.findByChannel(a2.id())).isEmpty();
    assertThat(repository.findByChannel(a1.id()))
        .extracting(ChannelOverwrite::targetId)
        .containsExactly(y);
    assertThat(repository.findByChannel(b1.id()))
        .extracting(ChannelOverwrite::targetId)
        .containsExactly(x);
  }

  // Ghi overwrite trong một transaction
  private void upsert(Channel channel, OverwriteTarget type, UUID target, long allow, long deny) {
    tx.executeWithoutResult(
        s ->
            repository.upsert(
                channel.id(),
                new ChannelOverwrite(
                    type, target, new PermissionSet(allow), new PermissionSet(deny))));
  }

  // Gỡ overwrite trong một transaction
  private boolean remove(Channel channel, OverwriteTarget type, UUID target) {
    return Boolean.TRUE.equals(tx.execute(s -> repository.remove(channel.id(), type, target)));
  }

  // Lưu một kênh không category làm chỗ chứa overwrite
  private Channel savedChannel(Server server, String name) {
    Channel channel =
        Channel.create(server.id(), null, new ChannelName(name), new Position(0), now());
    channels.save(channel);
    return channel;
  }

  // Lưu một server mới
  private Server savedServer() {
    Server server = Server.create(new ServerName("s"), UserId.newId(), now());
    servers.save(server);
    return server;
  }

  // Thời điểm hiện tại cắt về micro để khớp timestamptz
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}
