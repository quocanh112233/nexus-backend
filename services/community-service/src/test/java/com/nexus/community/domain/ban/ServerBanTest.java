package com.nexus.community.domain.ban;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// Kiểm thử bất biến và sự kiện của aggregate ServerBan
class ServerBanTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private final ServerId serverId = ServerId.newId();
  private final UserId target = UserId.newId();
  private final UserId admin = UserId.newId();

  // Kịch bản: tạo ban với lý do hợp lệ có khoảng trắng hai đầu và wasMember true
  // Input: create(server, target, admin, "  spam  ", true, NOW)
  // Output: reason đã trim, getter đúng, 1 MemberBanned wasMember true, pullEvents lần hai rỗng
  @Test
  void createTrimsReasonAndEmitsMemberBanned() {
    ServerBan ban = ServerBan.create(serverId, target, admin, "  spam  ", true, NOW);

    assertThat(ban.serverId()).isEqualTo(serverId);
    assertThat(ban.userId()).isEqualTo(target);
    assertThat(ban.bannedBy()).isEqualTo(admin);
    assertThat(ban.reason()).isEqualTo("spam");
    assertThat(ban.createdAt()).isEqualTo(NOW);
    assertThat(ban.pullEvents())
        .singleElement()
        .isEqualTo(new MemberBanned(serverId, target, admin, "spam", true));
    assertThat(ban.pullEvents()).isEmpty();
  }

  // Kịch bản: lý do null hoặc toàn khoảng trắng thì coi như không có lý do
  // Input: create với reason null (wasMember false) và với reason "   "
  // Output: reason null ở cả hai, event mang reason null và wasMember tương ứng
  @Test
  void blankOrNullReasonBecomesNull() {
    ServerBan fromNull = ServerBan.create(serverId, target, admin, null, false, NOW);
    ServerBan fromBlank = ServerBan.create(serverId, target, admin, "   ", true, NOW);

    assertThat(fromNull.reason()).isNull();
    assertThat(fromBlank.reason()).isNull();
    assertThat(fromNull.pullEvents())
        .containsExactly(new MemberBanned(serverId, target, admin, null, false));
  }

  // Kịch bản: biên độ dài lý do 512 và 513 ký tự
  // Input: create với 512 ký tự rồi với 513 ký tự
  // Output: 512 nhận, 513 ném DomainValidationException
  @Test
  void reasonLongerThan512Rejected() {
    assertThat(ServerBan.create(serverId, target, admin, "a".repeat(512), false, NOW).reason())
        .hasSize(512);

    assertThatThrownBy(() -> ServerBan.create(serverId, target, admin, "a".repeat(513), false, NOW))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: dựng lại từ lưu trữ không phát event, gỡ ban phát MemberUnbanned
  // Input: rehydrate rồi lift(admin)
  // Output: rehydrate không event; sau lift có 1 MemberUnbanned(server, target, admin)
  @Test
  void rehydrateSilentAndLiftEmitsMemberUnbanned() {
    ServerBan ban = ServerBan.rehydrate(serverId, target, admin, "r", NOW);
    assertThat(ban.pullEvents()).isEmpty();

    ban.lift(admin);

    assertThat(ban.pullEvents()).containsExactly(new MemberUnbanned(serverId, target, admin));
  }
}
