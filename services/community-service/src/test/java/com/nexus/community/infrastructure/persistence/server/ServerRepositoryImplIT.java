package com.nexus.community.infrastructure.persistence.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.category.Category;
import com.nexus.community.domain.category.CategoryName;
import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelName;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.Role;
import com.nexus.community.domain.server.RoleChanges;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.Position;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra ServerRepositoryImpl với Postgres thật: lưu kèm role, khóa lạc quan, cascade, lọc member
@SpringBootTest
class ServerRepositoryImplIT extends CommunityIntegrationTest {

  @Autowired private ServerRepository repository;
  @Autowired private CategoryRepository categories;
  @Autowired private ChannelRepository channels;
  @Autowired private MemberRepository members;
  @Autowired private TransactionTemplate tx;

  // Kịch bản: lưu server mới rồi tìm lại
  // Input: Server.create với tên và chủ ngẫu nhiên
  // Output: findById trả server bằng bản đã lưu, có đúng một role @everyone mặc định
  @Test
  void savesServerWithEveryoneRole() {
    Server server = newServer();

    repository.save(server);

    Server found = repository.findById(server.id()).orElseThrow();
    assertThat(found).usingRecursiveComparison().ignoringFields("events").isEqualTo(server);
    assertThat(found.roles()).hasSize(1);
    assertThat(found.roles().get(0).isDefault()).isTrue();
    assertThat(found.version()).isEqualTo(0L);
  }

  // Kịch bản: hai bản cùng version cùng đổi tên, bản lưu sau đã cũ
  // Input: tải hai bản của một server, rename cả hai, lưu bản 1 rồi bản 2
  // Output: bản 1 lưu được và đọc lại version 1, lưu bản 2 ném lỗi khóa lạc quan
  @Test
  void staleVersionThrowsOptimisticLock() {
    Server saved = newServer();
    repository.save(saved);
    Server first = repository.findById(saved.id()).orElseThrow();
    Server second = repository.findById(saved.id()).orElseThrow();
    first.rename(new ServerName("First"), now());
    second.rename(new ServerName("Second"), now());

    repository.save(first);

    assertThat(repository.findById(saved.id()).orElseThrow().name().value()).isEqualTo("First");
    assertThat(repository.findById(saved.id()).orElseThrow().version()).isEqualTo(1L);
    assertThatThrownBy(() -> repository.save(second))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  // Kịch bản: xóa server đã có category, kênh và thành viên
  // Input: server kèm một category, một kênh, một thành viên rồi delete
  // Output: server, category, kênh, thành viên và role đều biến mất
  @Test
  void deleteCascadesCategoriesChannelsMembers() {
    Server server = newServer();
    repository.save(server);
    Category category = Category.create(server.id(), new CategoryName("c"), new Position(0), now());
    categories.save(category);
    Channel channel =
        Channel.create(
            server.id(), category.id(), new ChannelName("general"), new Position(0), now());
    channels.save(channel);
    Member member = Member.join(server.id(), server.ownerId(), now());
    tx.executeWithoutResult(s -> members.addIfAbsent(member));

    repository.delete(server.id());

    assertThat(repository.findById(server.id())).isEmpty();
    assertThat(categories.findById(category.id())).isEmpty();
    assertThat(channels.findById(channel.id())).isEmpty();
    assertThat(members.find(server.id(), server.ownerId())).isEmpty();
  }

  // Kịch bản: liệt kê server theo thành viên
  // Input: user tham gia 2 server tạo lần lượt, một server khác không có user
  // Output: chỉ trả 2 server của user, đúng thứ tự created_at tăng dần
  @Test
  void findAllByMemberReturnsOnlyMine() {
    UserId me = UserId.newId();
    Server older = Server.create(new ServerName("older"), me, now().minusSeconds(10));
    Server newer = Server.create(new ServerName("newer"), me, now());
    Server other = newServer();
    for (Server s : new Server[] {newer, older, other}) {
      repository.save(s);
    }
    tx.executeWithoutResult(
        s -> {
          members.addIfAbsent(Member.join(older.id(), me, now()));
          members.addIfAbsent(Member.join(newer.id(), me, now()));
        });

    assertThat(repository.findAllByMember(me))
        .extracting(Server::id)
        .containsExactly(older.id(), newer.id());
    assertThat(repository.findAllByMember(me).get(0).roles()).hasSize(1);
  }

  // Kịch bản: thêm 3 role, đổi chỗ hai role trong một lần save, xóa một role rồi tải lại
  // Input: lưu server, createRole a b c, save; đổi vị trí c về 1 và save; removeRole b và save
  // Output: mỗi lần tải lại khớp bản gốc, không vi phạm unique, version tăng theo từng lần save
  @Test
  void syncsRolesWithSwapAndDelete() {
    Server server = newServer();
    repository.save(server);
    Server loaded = repository.findById(server.id()).orElseThrow();
    Role c = loaded.createRole("c", 1, PermissionSet.EVERYONE_DEFAULT, null, now());
    Role b = loaded.createRole("b", 2, PermissionSet.ALL, null, now());
    loaded.createRole("a", 3, new PermissionSet(0), null, now());
    repository.save(loaded);
    Server afterCreate = repository.findById(server.id()).orElseThrow();
    assertThat(afterCreate)
        .usingRecursiveComparison()
        .ignoringFields("events", "version")
        .isEqualTo(loaded);
    assertThat(afterCreate.version()).isEqualTo(1L);
    assertThat(afterCreate.roles())
        .extracting(Role::name)
        .containsExactly("@everyone", "a", "b", "c");

    afterCreate.updateRole(c.id(), new RoleChanges(null, null, null, 1), now());
    repository.save(afterCreate);
    Server afterSwap = repository.findById(server.id()).orElseThrow();
    assertThat(afterSwap.roles())
        .extracting(Role::name)
        .containsExactly("@everyone", "c", "a", "b");
    assertThat(afterSwap.roles()).extracting(Role::position).containsExactly(0, 1, 2, 3);
    assertThat(afterSwap.version()).isEqualTo(2L);

    afterSwap.removeRole(b.id(), now());
    repository.save(afterSwap);
    Server afterDelete = repository.findById(server.id()).orElseThrow();
    assertThat(afterDelete.roles()).extracting(Role::name).containsExactly("@everyone", "c", "a");
    assertThat(afterDelete.roles()).extracting(Role::position).containsExactly(0, 1, 2);
    assertThat(afterDelete.version()).isEqualTo(3L);
  }

  // Kịch bản: hai bản cùng version cùng sửa role, bản lưu sau đã cũ
  // Input: server có role "r", tải hai bản, mỗi bản đổi tên role khác nhau, lưu bản 1 rồi bản 2
  // Output: bản 2 ném ObjectOptimisticLockingFailureException, role giữ tên của bản 1
  @Test
  void staleRoleEditThrowsOptimisticLock() {
    Server server = newServer();
    Role r = server.createRole("r", 0, new PermissionSet(0), null, now());
    repository.save(server);
    Server first = repository.findById(server.id()).orElseThrow();
    Server second = repository.findById(server.id()).orElseThrow();
    first.updateRole(r.id(), new RoleChanges("one", null, null, null), now());
    second.updateRole(r.id(), new RoleChanges("two", null, null, null), now());

    repository.save(first);

    assertThatThrownBy(() -> repository.save(second))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(repository.findById(server.id()).orElseThrow().findRole(r.id()).orElseThrow().name())
        .isEqualTo("one");
  }

  // Dựng server mới chưa lưu với chủ ngẫu nhiên
  private static Server newServer() {
    return Server.create(new ServerName("Server"), UserId.newId(), now());
  }

  // Thời điểm hiện tại cắt về micro để khớp timestamptz
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}
