package com.nexus.community.application.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// Kiểm tra ListMembersQuery lấy limit+1 dòng, cắt trang và dựng con trỏ
class ListMembersQueryTest {

  private final ServerRepository servers = mock(ServerRepository.class);
  private final MemberRepository members = mock(MemberRepository.class);
  private final ListMembersQuery query =
      new ListMembersQuery(new ServerAccess(servers, members), members);
  private final UserId caller = UserId.newId();
  private final Server server = Server.create(new ServerName("S"), caller, Instant.now());

  // Kịch bản: còn dòng thứ limit+1 thì cắt về limit và trả con trỏ là user cuối trang
  // Input: limit 2, repository trả 3 member cho yêu cầu 3 dòng
  // Output: page được gọi với limit 3, items có 2 phần tử, lastUserId là user thứ hai
  @Test
  void fetchesLimitPlusOneAndBuildsCursor() {
    List<Member> rows = List.of(member(), member(), member());
    allowCaller();
    when(members.page(server.id(), null, 3)).thenReturn(rows);

    MemberPage page = query.execute(server.id(), caller, null, 2);

    verify(members).page(server.id(), null, 3);
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(1).userId()).isEqualTo(rows.get(1).userId().value());
    assertThat(page.lastUserId()).isEqualTo(rows.get(1).userId().value());
    assertThat(page.items().get(0).roleIds()).isEmpty();
  }

  // Kịch bản: sau trang cuối hoặc đúng limit dòng thì không còn con trỏ
  // Input: repository trả 0 dòng sau con trỏ, rồi đúng 2 dòng cho limit 2
  // Output: items rỗng và lastUserId null; items 2 phần tử và lastUserId null
  @Test
  void emptyAfterLastPage() {
    UserId after = UserId.newId();
    allowCaller();
    when(members.page(server.id(), after, 3)).thenReturn(List.of());
    when(members.page(server.id(), null, 3)).thenReturn(List.of(member(), member()));

    MemberPage empty = query.execute(server.id(), caller, after, 2);
    MemberPage exact = query.execute(server.id(), caller, null, 2);

    assertThat(empty.items()).isEmpty();
    assertThat(empty.lastUserId()).isNull();
    assertThat(exact.items()).hasSize(2);
    assertThat(exact.lastUserId()).isNull();
  }

  // Cho caller qua kiểm tra thành viên của server
  private void allowCaller() {
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), caller))
        .thenReturn(Optional.of(Member.join(server.id(), caller, Instant.now())));
  }

  // Tạo một thành viên mới của server
  private Member member() {
    return Member.join(server.id(), UserId.newId(), Instant.now());
  }
}
