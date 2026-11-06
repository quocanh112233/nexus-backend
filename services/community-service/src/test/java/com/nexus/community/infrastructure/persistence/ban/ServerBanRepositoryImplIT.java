package com.nexus.community.infrastructure.persistence.ban;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.ban.ServerBan;
import com.nexus.community.domain.ban.ServerBanRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra ServerBanRepositoryImpl với Postgres thật
@SpringBootTest
class ServerBanRepositoryImplIT extends CommunityIntegrationTest {

  @Autowired private ServerBanRepository repository;
  @Autowired private ServerRepository servers;
  @Autowired private TransactionTemplate tx;
  @Autowired private JdbcTemplate jdbc;

  // Kịch bản: thêm cùng một ban hai lần rồi đọc lại
  // Input: addIfAbsent hai lần với cùng server và user, lý do có và không có
  // Output: lần 1 true, lần 2 false; find trả ban với đủ trường đã lưu; lý do null giữ null
  @Test
  void addIfAbsentReturnsFalseOnSecondInsert() {
    Server server = savedServer();
    ServerBan ban =
        ServerBan.create(server.id(), UserId.newId(), UserId.newId(), "spam", true, now());

    assertThat(add(ban)).isTrue();
    assertThat(add(ban)).isFalse();

    assertThat(repository.find(server.id(), ban.userId()).orElseThrow())
        .usingRecursiveComparison()
        .ignoringFields("events")
        .isEqualTo(ban);
    ServerBan noReason =
        ServerBan.create(server.id(), UserId.newId(), ban.bannedBy(), null, false, now());
    add(noReason);
    assertThat(repository.find(server.id(), noReason.userId()).orElseThrow().reason()).isNull();
    assertThat(repository.find(server.id(), UserId.newId())).isEmpty();
  }

  // Kịch bản: gỡ ban rồi gỡ lại
  // Input: thêm một ban, remove hai lần
  // Output: remove lần 1 true và find rỗng; lần 2 false
  @Test
  void removeReturnsTrueOnlyWhenRowDeleted() {
    Server server = savedServer();
    ServerBan ban =
        ServerBan.create(server.id(), UserId.newId(), UserId.newId(), null, false, now());
    add(ban);

    assertThat(remove(server, ban.userId())).isTrue();
    assertThat(repository.find(server.id(), ban.userId())).isEmpty();
    assertThat(remove(server, ban.userId())).isFalse();
  }

  // Kịch bản: duyệt 25 ban bằng keyset limit 10, ban của server khác không lẫn vào
  // Input: page(null,10) rồi page(user cuối trang trước,10) cho tới hết; server B có 1 ban
  // Output: 10, 10, 5 phần tử, gộp đủ 25 user theo user_id tăng dần, không có ban server B
  @Test
  void pageWalksAllBansByKeyset() {
    Server server = savedServer();
    Server other = savedServer();
    UserId admin = UserId.newId();
    List<UserId> added = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      ServerBan ban = ServerBan.create(server.id(), UserId.newId(), admin, null, false, now());
      add(ban);
      added.add(ban.userId());
    }
    add(ServerBan.create(other.id(), UserId.newId(), admin, null, false, now()));

    List<UserId> all = new ArrayList<>();
    List<Integer> sizes = new ArrayList<>();
    UserId after = null;
    while (true) {
      List<ServerBan> page = repository.page(server.id(), after, 10);
      if (page.isEmpty()) {
        break;
      }
      sizes.add(page.size());
      page.forEach(b -> all.add(b.userId()));
      after = page.get(page.size() - 1).userId();
    }

    assertThat(sizes).containsExactly(10, 10, 5);
    assertThat(all)
        .containsExactlyInAnyOrderElementsOf(added)
        .isSortedAccordingTo((a, b) -> a.value().compareTo(b.value()));
  }

  // Kịch bản: xóa server kéo theo xóa ban của nó
  // Input: thêm ban rồi ServerRepository.delete(server)
  // Output: bảng server_bans không còn dòng nào của server
  @Test
  void deletingServerCascadesBans() {
    Server server = savedServer();
    add(ServerBan.create(server.id(), UserId.newId(), UserId.newId(), null, false, now()));

    tx.executeWithoutResult(s -> servers.delete(server.id()));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from community.server_bans where server_id = ?",
                Long.class,
                server.id().value()))
        .isZero();
  }

  // Gọi addIfAbsent trong một transaction vì lệnh INSERT cần transaction của nơi gọi
  private boolean add(ServerBan ban) {
    return Boolean.TRUE.equals(tx.execute(s -> repository.addIfAbsent(ban)));
  }

  // Gọi remove trong một transaction vì lệnh DELETE cần transaction của nơi gọi
  private boolean remove(Server server, UserId user) {
    return Boolean.TRUE.equals(tx.execute(s -> repository.remove(server.id(), user)));
  }

  // Lưu một server mới làm chỗ chứa cho ban
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
