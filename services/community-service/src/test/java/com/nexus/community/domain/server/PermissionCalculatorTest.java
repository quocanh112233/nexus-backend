package com.nexus.community.domain.server;

import static com.nexus.community.domain.server.Permission.ADD_REACTIONS;
import static com.nexus.community.domain.server.Permission.ADMINISTRATOR;
import static com.nexus.community.domain.server.Permission.ATTACH_FILES;
import static com.nexus.community.domain.server.Permission.KICK_MEMBERS;
import static com.nexus.community.domain.server.Permission.MANAGE_ROLES;
import static com.nexus.community.domain.server.Permission.MANAGE_SERVER;
import static com.nexus.community.domain.server.Permission.SEND_MESSAGES;
import static com.nexus.community.domain.server.Permission.VIEW_CHANNEL;
import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.domain.channel.ChannelOverwrite;
import com.nexus.community.domain.channel.OverwriteTarget;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm thử thuật toán tính quyền hiệu lực theo từng bước của permission-model
class PermissionCalculatorTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final PermissionSet NONE = new PermissionSet(0);

  private final UserId ownerId = UserId.newId();
  private final UserId userId = UserId.newId();
  private final Role everyone =
      new Role(RoleId.newId(), "@everyone", 0, PermissionSet.EVERYONE_DEFAULT, 0, true);

  // Dựng tập quyền từ danh sách quyền
  private static PermissionSet set(Permission... permissions) {
    return NONE.with(permissions);
  }

  // Dựng role thường với vị trí và quyền cho trước
  private static Role role(int position, Permission... permissions) {
    return new Role(RoleId.newId(), "r" + position, 0, set(permissions), position, false);
  }

  // Dựng server có chủ cố định, @everyone và các role thêm
  private Server server(Role... extra) {
    List<Role> roles = new ArrayList<>(List.of(everyone));
    roles.addAll(List.of(extra));
    return Server.rehydrate(ServerId.newId(), new ServerName("Nexus"), ownerId, roles, NOW, NOW, 0);
  }

  // Dựng member thường mang các role cho trước
  private Member member(Role... roles) {
    return Member.rehydrate(
        ServerId.newId(), userId, NOW, java.util.Arrays.stream(roles).map(Role::id).toList());
  }

  // Dựng overwrite theo role
  private static ChannelOverwrite roleOw(Role role, PermissionSet allow, PermissionSet deny) {
    return new ChannelOverwrite(OverwriteTarget.ROLE, role.id().value(), allow, deny);
  }

  // Dựng overwrite theo member
  private static ChannelOverwrite memberOw(UserId id, PermissionSet allow, PermissionSet deny) {
    return new ChannelOverwrite(OverwriteTarget.MEMBER, id.value(), allow, deny);
  }

  // Kịch bản: chủ server có mọi quyền ở phạm vi server
  // Input: server(Server, Member) với member là owner
  // Output: PermissionSet.ALL
  @Test
  void serverOwnerGetsAll() {
    Member owner = Member.rehydrate(ServerId.newId(), ownerId, NOW, List.of());
    assertThat(PermissionCalculator.server(server(), owner)).isEqualTo(PermissionSet.ALL);
  }

  // Kịch bản: quyền server là hợp của @everyone và các role của member
  // Input: member có role KICK_MEMBERS và role MANAGE_ROLES
  // Output: EVERYONE_DEFAULT cộng KICK_MEMBERS và MANAGE_ROLES
  @Test
  void serverUnionsEveryoneAndRoles() {
    Role a = role(1, KICK_MEMBERS);
    Role b = role(2, MANAGE_ROLES);
    PermissionSet result = PermissionCalculator.server(server(a, b), member(a, b));
    assertThat(result).isEqualTo(PermissionSet.EVERYONE_DEFAULT.with(KICK_MEMBERS, MANAGE_ROLES));
  }

  // Kịch bản: role chỉ có ADMINISTRATOR cho mọi quyền server
  // Input: member có role ADMINISTRATOR
  // Output: PermissionSet.ALL
  @Test
  void serverAdministratorGetsAll() {
    Role admin = role(5, ADMINISTRATOR);
    assertThat(PermissionCalculator.server(server(admin), member(admin)))
        .isEqualTo(PermissionSet.ALL);
  }

  // Kịch bản: role member không có trong server bị bỏ qua
  // Input: member mang roleId lạ
  // Output: chỉ có EVERYONE_DEFAULT
  @Test
  void serverIgnoresUnknownRoleId() {
    Member m = Member.rehydrate(ServerId.newId(), userId, NOW, List.of(RoleId.newId()));
    assertThat(PermissionCalculator.server(server(), m)).isEqualTo(PermissionSet.EVERYONE_DEFAULT);
  }

  // Kịch bản: bước 1 chủ server luôn ALL dù overwrite chặn hết
  // Input: owner, overwrite @everyone deny VIEW_CHANNEL
  // Output: PermissionSet.ALL
  @Test
  void channelOwnerGetsAll() {
    Member owner = Member.rehydrate(ServerId.newId(), ownerId, NOW, List.of());
    List<ChannelOverwrite> ows = List.of(roleOw(everyone, NONE, set(VIEW_CHANNEL)));
    assertThat(PermissionCalculator.channel(server(), owner, ows)).isEqualTo(PermissionSet.ALL);
  }

  // Kịch bản: bước 2 không overwrite thì quyền kênh bằng base
  // Input: member thường, danh sách overwrite rỗng
  // Output: EVERYONE_DEFAULT
  @Test
  void channelWithoutOverwritesEqualsBase() {
    assertThat(PermissionCalculator.channel(server(), member(), List.of()))
        .isEqualTo(PermissionSet.EVERYONE_DEFAULT);
  }

  // Kịch bản: bước 3 ADMINISTRATOR vẫn thấy kênh bị deny VIEW_CHANNEL (ví dụ 2)
  // Input: role Admin, overwrite @everyone deny VIEW_CHANNEL
  // Output: PermissionSet.ALL
  @Test
  void channelAdministratorIgnoresOverwrites() {
    Role admin = role(5, ADMINISTRATOR);
    List<ChannelOverwrite> ows = List.of(roleOw(everyone, NONE, set(VIEW_CHANNEL)));
    assertThat(PermissionCalculator.channel(server(admin), member(admin), ows))
        .isEqualTo(PermissionSet.ALL);
  }

  // Kịch bản: bước 4 overwrite @everyone deny SEND_MESSAGES với member thường (ví dụ 1)
  // Input: member không role, overwrite @everyone deny SEND_MESSAGES
  // Output: không có SEND_MESSAGES, vẫn có VIEW_CHANNEL
  @Test
  void channelEveryoneDenyRemovesBit() {
    List<ChannelOverwrite> ows = List.of(roleOw(everyone, NONE, set(SEND_MESSAGES)));
    PermissionSet p = PermissionCalculator.channel(server(), member(), ows);
    assertThat(p.has(SEND_MESSAGES)).isFalse();
    assertThat(p.has(VIEW_CHANNEL)).isTrue();
  }

  // Kịch bản: bước 4 overwrite role Mod allow thắng @everyone deny (ví dụ 1)
  // Input: @everyone deny SEND_MESSAGES, role Mod allow SEND_MESSAGES
  // Output: Mod có SEND_MESSAGES
  @Test
  void channelRoleAllowBeatsEveryoneDeny() {
    Role mod = role(2);
    List<ChannelOverwrite> ows =
        List.of(roleOw(everyone, NONE, set(SEND_MESSAGES)), roleOw(mod, set(SEND_MESSAGES), NONE));
    assertThat(PermissionCalculator.channel(server(mod), member(mod), ows).has(SEND_MESSAGES))
        .isTrue();
  }

  // Kịch bản: bước 4 nhiều role gộp deny rồi allow, allow thắng deny giữa các role
  // Input: role A deny SEND_MESSAGES, role B allow SEND_MESSAGES
  // Output: có SEND_MESSAGES
  @Test
  void channelMultipleRolesMergeDenyThenAllow() {
    Role a = role(1);
    Role b = role(2);
    List<ChannelOverwrite> ows =
        List.of(roleOw(a, NONE, set(SEND_MESSAGES)), roleOw(b, set(SEND_MESSAGES), NONE));
    assertThat(PermissionCalculator.channel(server(a, b), member(a, b), ows).has(SEND_MESSAGES))
        .isTrue();
  }

  // Kịch bản: bước 4 MEMBER deny thắng role allow (ví dụ 3)
  // Input: role allow SEND_MESSAGES, overwrite MEMBER của user deny SEND_MESSAGES
  // Output: không có SEND_MESSAGES
  @Test
  void channelMemberDenyBeatsRoleAllow() {
    Role r = role(1, SEND_MESSAGES);
    List<ChannelOverwrite> ows =
        List.of(roleOw(r, set(SEND_MESSAGES), NONE), memberOw(userId, NONE, set(SEND_MESSAGES)));
    assertThat(PermissionCalculator.channel(server(r), member(r), ows).has(SEND_MESSAGES))
        .isFalse();
  }

  // Kịch bản: bước 4 MEMBER allow thắng role deny
  // Input: @everyone deny SEND_MESSAGES, overwrite MEMBER của user allow SEND_MESSAGES
  // Output: có SEND_MESSAGES
  @Test
  void channelMemberAllowBeatsRoleDeny() {
    List<ChannelOverwrite> ows =
        List.of(
            roleOw(everyone, NONE, set(SEND_MESSAGES)), memberOw(userId, set(SEND_MESSAGES), NONE));
    assertThat(PermissionCalculator.channel(server(), member(), ows).has(SEND_MESSAGES)).isTrue();
  }

  // Kịch bản: bước 4 bỏ qua overwrite của role mà member không có và của member khác
  // Input: overwrite role lạ deny SEND_MESSAGES, overwrite MEMBER khác deny SEND_MESSAGES
  // Output: vẫn bằng EVERYONE_DEFAULT
  @Test
  void channelIgnoresForeignOverwrites() {
    Role other = role(3);
    List<ChannelOverwrite> ows =
        List.of(
            roleOw(other, NONE, set(SEND_MESSAGES)),
            memberOw(UserId.newId(), NONE, set(SEND_MESSAGES)));
    assertThat(PermissionCalculator.channel(server(other), member(), ows))
        .isEqualTo(PermissionSet.EVERYONE_DEFAULT);
  }

  // Kịch bản: overwrite với targetId lạ kiểu UUID ngẫu nhiên không gây lỗi
  // Input: overwrite ROLE với UUID ngẫu nhiên allow MANAGE_ROLES
  // Output: không có MANAGE_ROLES
  @Test
  void channelIgnoresUnknownTarget() {
    List<ChannelOverwrite> ows =
        List.of(
            new ChannelOverwrite(OverwriteTarget.ROLE, UUID.randomUUID(), set(MANAGE_ROLES), NONE));
    assertThat(PermissionCalculator.channel(server(), member(), ows).has(MANAGE_ROLES)).isFalse();
  }

  // Kịch bản: bước 5 thiếu VIEW_CHANNEL thì không còn quyền nào
  // Input: @everyone deny VIEW_CHANNEL
  // Output: PermissionSet rỗng
  @Test
  void channelWithoutViewChannelIsEmpty() {
    List<ChannelOverwrite> ows = List.of(roleOw(everyone, NONE, set(VIEW_CHANNEL)));
    assertThat(PermissionCalculator.channel(server(), member(), ows)).isEqualTo(NONE);
  }

  // Kịch bản: bước 6 thiếu SEND_MESSAGES thì mất ATTACH_FILES, giữ quyền khác
  // Input: @everyone deny SEND_MESSAGES, không deny ATTACH_FILES
  // Output: không có ATTACH_FILES, còn ADD_REACTIONS
  @Test
  void channelWithoutSendDropsAttachFiles() {
    List<ChannelOverwrite> ows = List.of(roleOw(everyone, NONE, set(SEND_MESSAGES)));
    PermissionSet p = PermissionCalculator.channel(server(), member(), ows);
    assertThat(p.has(ATTACH_FILES)).isFalse();
    assertThat(p.has(ADD_REACTIONS)).isTrue();
  }

  // Kịch bản: overwrite deny trên bit phạm vi server bị bỏ qua
  // Input: role có KICK_MEMBERS, overwrite role deny KICK_MEMBERS
  // Output: vẫn có KICK_MEMBERS
  @Test
  void channelDenyOnServerScopeBitIgnored() {
    Role r = role(1, KICK_MEMBERS);
    List<ChannelOverwrite> ows = List.of(roleOw(r, NONE, set(KICK_MEMBERS)));
    assertThat(PermissionCalculator.channel(server(r), member(r), ows).has(KICK_MEMBERS)).isTrue();
  }

  // Kịch bản: overwrite allow trên bit phạm vi server không cấp thêm quyền
  // Input: overwrite @everyone allow MANAGE_SERVER
  // Output: không có MANAGE_SERVER
  @Test
  void channelAllowOnServerScopeBitIgnored() {
    List<ChannelOverwrite> ows = List.of(roleOw(everyone, set(MANAGE_SERVER), NONE));
    assertThat(PermissionCalculator.channel(server(), member(), ows).has(MANAGE_SERVER)).isFalse();
  }
}
