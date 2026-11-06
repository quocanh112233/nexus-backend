package com.nexus.community.infrastructure.persistence.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra MemberRepositoryImpl với Postgres thật
@SpringBootTest
class MemberRepositoryImplIT extends CommunityIntegrationTest {

  @Autowired private MemberRepository repository;
  @Autowired private ServerRepository servers;
  @Autowired private TransactionTemplate tx;

  // Kịch bản: thêm cùng một thành viên hai lần
  // Input: addIfAbsent hai lần với cùng server và user
  // Output: lần 1 true, lần 2 false, find trả thành viên với joinedAt đã lưu
  @Test
  void addIfAbsentReturnsFalseOnSecondInsert() {
    Server server = savedServer();
    Member member = Member.join(server.id(), UserId.newId(), now());

    assertThat(add(member)).isTrue();
    assertThat(add(member)).isFalse();

    assertThat(repository.find(server.id(), member.userId()).orElseThrow())
        .usingRecursiveComparison()
        .ignoringFields("events")
        .isEqualTo(member);
  }

  // Kịch bản: duyệt 25 thành viên bằng keyset limit 10
  // Input: page(null,10) rồi page(user cuối trang trước,10) cho tới hết
  // Output: ba trang 10, 10, 5 không trùng không sót và tăng dần theo user_id
  @Test
  void pageUsesKeysetByUserId() {
    Server server = savedServer();
    List<UserId> added = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      Member m = Member.join(server.id(), UserId.newId(), now());
      add(m);
      added.add(m.userId());
    }

    List<Member> p1 = repository.page(server.id(), null, 10);
    List<Member> p2 = repository.page(server.id(), p1.get(9).userId(), 10);
    List<Member> p3 = repository.page(server.id(), p2.get(9).userId(), 10);

    assertThat(List.of(p1.size(), p2.size(), p3.size())).containsExactly(10, 10, 5);
    List<UserId> all = new ArrayList<>();
    for (List<Member> p : List.of(p1, p2, p3)) {
      p.forEach(m -> all.add(m.userId()));
    }
    assertThat(all)
        .containsExactlyInAnyOrderElementsOf(added)
        .isSortedAccordingTo((a, b) -> a.value().compareTo(b.value()));
  }

  // Kịch bản: gỡ thành viên rồi tìm lại
  // Input: thêm một thành viên rồi remove
  // Output: find trả rỗng
  @Test
  void removeThenFindEmpty() {
    Server server = savedServer();
    Member member = Member.join(server.id(), UserId.newId(), now());
    add(member);

    repository.remove(server.id(), member.userId());

    assertThat(repository.find(server.id(), member.userId())).isEmpty();
  }

  // Kịch bản: đếm thành viên theo server
  // Input: 3 thành viên ở server A, 1 thành viên ở server B
  // Output: count(A) là 3 và count(B) là 1
  @Test
  void countCountsMembers() {
    Server a = savedServer();
    Server b = savedServer();
    for (int i = 0; i < 3; i++) {
      add(Member.join(a.id(), UserId.newId(), now()));
    }
    add(Member.join(b.id(), UserId.newId(), now()));

    assertThat(repository.count(a.id())).isEqualTo(3L);
    assertThat(repository.count(b.id())).isEqualTo(1L);
  }

  // Gọi addIfAbsent trong một transaction vì lệnh INSERT cần transaction của nơi gọi
  private boolean add(Member member) {
    return Boolean.TRUE.equals(tx.execute(s -> repository.addIfAbsent(member)));
  }

  // Lưu một server mới làm chỗ chứa cho thành viên
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
