package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelOverwrite;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.channel.OverwriteTarget;
import com.nexus.community.domain.server.PermissionSet;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra overwrite quyền theo kênh qua REST: tính quyền, ẩn kênh, phân quyền, dọn dẹp, outbox
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OverwriteIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final long ADMINISTRATOR = 1;
  private static final long MANAGE_ROLES = 4;
  private static final long VIEW_CHANNEL = 32;
  private static final long ADD_REACTIONS = 512;
  private static final long SEND_MESSAGES = 64;
  private static final long MANAGE_MESSAGES = 1024;
  private static final long BAN_MEMBERS = 4096;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ChannelOverwriteRepository overwrites;
  @LocalServerPort private int port;

  // Dữ liệu dựng sẵn: owner, member thường, Mod, manager (MANAGE_ROLES vị trí 2), admin, hai kênh
  private record Fx(
      UUID owner,
      UUID plain,
      UUID mod,
      UUID manager,
      UUID admin,
      String server,
      String general,
      String announcements,
      String everyone,
      String modRole) {}

  // Kịch bản: ví dụ announcements của permission-model §7 chạy đầu cuối qua API và permissions/@me
  // Input: @everyone deny SEND_MESSAGES và role Mod allow SEND_MESSAGES trên announcements
  // Output: member thường mất SEND ở announcements nhưng còn ở general; Mod có; outbox hai sự kiện
  @Test
  void announcementsScenario() throws Exception {
    Fx f = fx();

    assertThat(putRole(f, f.owner, f.announcements, f.everyone, 0, SEND_MESSAGES).statusCode())
        .isEqualTo(204);
    assertThat(putRole(f, f.owner, f.announcements, f.modRole, SEND_MESSAGES, 0).statusCode())
        .isEqualTo(204);

    assertThat(perm(f, f.plain, f.announcements) & SEND_MESSAGES).isZero();
    assertThat(perm(f, f.plain, f.announcements) & VIEW_CHANNEL).isEqualTo(VIEW_CHANNEL);
    assertThat(perm(f, f.plain, f.general) & SEND_MESSAGES).isEqualTo(SEND_MESSAGES);
    assertThat(perm(f, f.mod, f.announcements) & SEND_MESSAGES).isEqualTo(SEND_MESSAGES);
    assertThat(outbox(f.announcements)).hasSize(2);
    JsonNode event = MAPPER.readTree(outbox(f.announcements).get(0));
    assertThat(event.get("serverId").asText()).isEqualTo(f.server);
    assertThat(event.get("channelId").asText()).isEqualTo(f.announcements);
  }

  // Kịch bản: overwrite MEMBER deny thắng overwrite role allow
  // Input: Mod allow SEND_MESSAGES và @everyone deny SEND, rồi MEMBER mod deny SEND_MESSAGES
  // Output: Mod mất SEND ở announcements, mod vẫn còn SEND ở general
  @Test
  void memberDenyBeatsRoleAllow() throws Exception {
    Fx f = fx();
    putRole(f, f.owner, f.announcements, f.everyone, 0, SEND_MESSAGES);
    putRole(f, f.owner, f.announcements, f.modRole, SEND_MESSAGES, 0);
    assertThat(perm(f, f.mod, f.announcements) & SEND_MESSAGES).isEqualTo(SEND_MESSAGES);

    assertThat(
            put(f, f.owner, f.announcements, "member", f.mod.toString(), 0, SEND_MESSAGES)
                .statusCode())
        .isEqualTo(204);

    assertThat(perm(f, f.mod, f.announcements) & SEND_MESSAGES).isZero();
    assertThat(perm(f, f.mod, f.general) & SEND_MESSAGES).isEqualTo(SEND_MESSAGES);
  }

  // Kịch bản: deny VIEW_CHANNEL cho @everyone ẩn kênh khỏi member thường nhưng không ẩn khỏi admin
  // Input: @everyone deny VIEW_CHANNEL trên announcements rồi GET server và permissions/@me
  // Output: member thường không thấy kênh ở cả hai API; owner và admin vẫn thấy với đủ quyền
  @Test
  void denyViewChannelHidesChannelFromPlainMember() throws Exception {
    Fx f = fx();
    putRole(f, f.owner, f.announcements, f.everyone, 0, VIEW_CHANNEL);

    assertThat(channelIds(f, f.plain)).containsExactly(f.general);
    assertThat(channelIds(f, f.owner)).contains(f.general, f.announcements);
    assertThat(channelIds(f, f.admin)).contains(f.general, f.announcements);
    assertThat(meChannels(f, f.plain)).containsOnlyKeys(f.general);
    assertThat(meChannels(f, f.owner)).containsKeys(f.general, f.announcements);
    assertThat(meChannels(f, f.admin).get(f.announcements)).isEqualTo(PermissionSet.ALL.bits());
  }

  // Kịch bản: overwrite đích member theo thứ bậc, kể cả chính mình và chủ server
  // Input: manager (vị trí 2) PUT và DELETE cho chính mình, owner, admin (3), Mod (1), thường
  // Output: self, owner, admin 403 (PUT và DELETE); Mod và thường 204; owner đặt cho ai cũng 204
  @Test
  void memberTargetFollowsHierarchy() throws Exception {
    Fx f = fx();
    put(f, f.owner, f.announcements, "member", f.manager.toString(), 0, SEND_MESSAGES);
    put(f, f.owner, f.announcements, "member", f.admin.toString(), 0, SEND_MESSAGES);

    for (UUID target : List.of(f.manager, f.owner, f.admin)) {
      assertThat(put(f, f.manager, f.announcements, "member", target.toString(), 0, 0).statusCode())
          .isEqualTo(403);
    }
    assertThat(delete(f, f.manager, f.announcements, "member", f.manager.toString()).statusCode())
        .isEqualTo(403);
    assertThat(delete(f, f.manager, f.announcements, "member", f.admin.toString()).statusCode())
        .isEqualTo(403);
    assertThat(perm(f, f.manager, f.announcements) & SEND_MESSAGES).isZero();
    assertThat(
            put(f, f.manager, f.announcements, "member", f.mod.toString(), 0, ADD_REACTIONS)
                .statusCode())
        .isEqualTo(204);
    assertThat(
            put(f, f.manager, f.announcements, "member", f.plain.toString(), 0, ADD_REACTIONS)
                .statusCode())
        .isEqualTo(204);
    assertThat(delete(f, f.manager, f.announcements, "member", f.mod.toString()).statusCode())
        .isEqualTo(204);

    for (UUID target : List.of(f.owner, f.manager, f.admin)) {
      assertThat(put(f, f.owner, f.announcements, "member", target.toString(), 0, 64).statusCode())
          .isEqualTo(204);
    }
    assertThat(delete(f, f.owner, f.announcements, "member", f.owner.toString()).statusCode())
        .isEqualTo(204);
  }

  // Kịch bản: MANAGE_ROLES cấp qua @everyone không cho rank 0 đặt hay gỡ overwrite MEMBER rank 0
  // Input: owner cấp MANAGE_ROLES cho @everyone, plain PUT và DELETE overwrite MEMBER cho user khác
  // Output: cả PUT và DELETE đều 403 và không có hàng overwrite nào
  @Test
  void everyoneManageRolesCannotTouchEqualRankMemberOverwrite() throws Exception {
    Fx f = fx();
    UUID other = UUID.randomUUID();
    addMember(f.server, other);
    grantEveryoneManageRoles(f);

    assertThat(put(f, f.plain, f.announcements, "member", other.toString(), 0, 64).statusCode())
        .isEqualTo(403);
    assertThat(delete(f, f.plain, f.announcements, "member", other.toString()).statusCode())
        .isEqualTo(403);

    assertThat(overwrites.findByChannel(new ChannelId(UUID.fromString(f.announcements)))).isEmpty();
  }

  // Kịch bản: MANAGE_ROLES qua @everyone không cho rank 0 đổi overwrite của kênh mình không thấy
  // Input: owner ẩn announcements bằng deny VIEW của @everyone; plain và manager PUT hoặc DELETE nó
  // Output: PUT của plain 404, DELETE của manager 404, deny VIEW còn nguyên; owner PUT 204
  @Test
  void hiddenChannelOverwritesAreNotFoundForActorsWithoutView() throws Exception {
    Fx f = fx();
    putRole(f, f.owner, f.announcements, f.everyone, 0, VIEW_CHANNEL);
    grantEveryoneManageRoles(f);

    assertThat(putRole(f, f.plain, f.announcements, f.everyone, VIEW_CHANNEL, 0).statusCode())
        .isEqualTo(404);
    assertThat(delete(f, f.manager, f.announcements, "role", f.everyone).statusCode())
        .isEqualTo(404);

    assertThat(overwrites.findByChannel(new ChannelId(UUID.fromString(f.announcements))))
        .hasSize(1);
    assertThat(putRole(f, f.owner, f.announcements, f.everyone, VIEW_CHANNEL, 0).statusCode())
        .isEqualTo(204);
  }

  // Kịch bản: manager rank 1 tạo role thấp rồi cấp VIEW cho nó trên kênh ẩn để tự mở kênh
  // Input: owner ẩn announcements; manager POST role Sneaky rồi PUT allow VIEW cho Sneaky
  // Output: PUT 404 và kênh không có overwrite nào cho Sneaky
  @Test
  void managerCannotGrantViewOnHiddenChannelThroughLowerRole() throws Exception {
    Fx f = fx();
    putRole(f, f.owner, f.announcements, f.everyone, 0, VIEW_CHANNEL);
    HttpResponse<String> created =
        raw("POST", f.server + "/roles", f.manager, "{\"name\":\"Sneaky\",\"position\":1}");
    assertThat(created.statusCode()).isEqualTo(201);
    String sneaky = MAPPER.readTree(created.body()).get("id").asText();

    assertThat(putRole(f, f.manager, f.announcements, sneaky, VIEW_CHANNEL, 0).statusCode())
        .isEqualTo(404);

    assertThat(overwrites.findByChannel(new ChannelId(UUID.fromString(f.announcements))))
        .hasSize(1);
  }

  // Kịch bản: người xem được kênh nhưng thiếu bit tại kênh không cấp hay gỡ deny của bit đó được
  // Input: owner deny SEND cho @everyone; manager PUT allow SEND, PUT rỗng, DELETE, PUT reactions
  // Output: ba lần đầu 403, deny còn nguyên; PUT ADD_REACTIONS cho Mod 204; owner DELETE 204
  @Test
  void channelLevelBitsLimitGrantAndLiftingDeny() throws Exception {
    Fx f = fx();
    putRole(f, f.owner, f.announcements, f.everyone, 0, SEND_MESSAGES);

    assertThat(putRole(f, f.manager, f.announcements, f.everyone, SEND_MESSAGES, 0).statusCode())
        .isEqualTo(403);
    assertThat(putRole(f, f.manager, f.announcements, f.everyone, 0, 0).statusCode())
        .isEqualTo(403);
    assertThat(delete(f, f.manager, f.announcements, "role", f.everyone).statusCode())
        .isEqualTo(403);

    assertThat(overwrites.findByChannel(new ChannelId(UUID.fromString(f.announcements))))
        .hasSize(1);
    assertThat(putRole(f, f.manager, f.announcements, f.modRole, ADD_REACTIONS, 0).statusCode())
        .isEqualTo(204);
    assertThat(delete(f, f.owner, f.announcements, "role", f.everyone).statusCode()).isEqualTo(204);
  }

  // Kịch bản: PUT lặp ghi đè hàng cũ và mỗi lần PUT phát đúng một sự kiện
  // Input: PUT cùng đích hai lần với allow khác nhau, đọc lại qua repository
  // Output: một hàng duy nhất mang giá trị mới nhất; outbox đúng hai sự kiện
  @Test
  void putIsIdempotentUpsert() throws Exception {
    Fx f = fx();

    putRole(f, f.owner, f.announcements, f.modRole, SEND_MESSAGES, 0);
    putRole(f, f.owner, f.announcements, f.modRole, 0, SEND_MESSAGES);

    List<ChannelOverwrite> rows =
        overwrites.findByChannel(new ChannelId(UUID.fromString(f.announcements)));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).type()).isEqualTo(OverwriteTarget.ROLE);
    assertThat(rows.get(0).targetId()).isEqualTo(UUID.fromString(f.modRole));
    assertThat(rows.get(0).allow().bits()).isZero();
    assertThat(rows.get(0).deny().bits()).isEqualTo(SEND_MESSAGES);
    assertThat(outbox(f.announcements)).hasSize(2);
  }

  // Kịch bản: DELETE gỡ overwrite, phát sự kiện, gỡ lại hoặc gỡ cái chưa có thì 404
  // Input: PUT @everyone deny SEND rồi DELETE hai lần, targetType viết hoa
  // Output: DELETE đầu 204 và quyền SEND trở lại, outbox hai sự kiện; DELETE sau 404 NOT_FOUND
  @Test
  void deleteRemovesOverwrite() throws Exception {
    Fx f = fx();
    putRole(f, f.owner, f.announcements, f.everyone, 0, SEND_MESSAGES);
    assertThat(perm(f, f.plain, f.announcements) & SEND_MESSAGES).isZero();

    assertThat(delete(f, f.owner, f.announcements, "ROLE", f.everyone).statusCode()).isEqualTo(204);

    assertThat(perm(f, f.plain, f.announcements) & SEND_MESSAGES).isEqualTo(SEND_MESSAGES);
    assertThat(outbox(f.announcements)).hasSize(2);
    HttpResponse<String> again = delete(f, f.owner, f.announcements, "role", f.everyone);
    assertThat(again.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(again.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(outbox(f.announcements)).hasSize(2);
  }

  // Kịch bản: bit sai bị từ chối 400 và không ghi gì
  // Input: BAN_MEMBERS trong allow, trong deny, allow giao deny, bit âm, bit ngoài ALL, body hỏng
  // Output: mọi trường hợp 400 VALIDATION_FAILED, không có hàng overwrite và không có outbox
  @Test
  void invalidBitsReturn400() throws Exception {
    Fx f = fx();
    String path = path(f, f.announcements, "role", f.modRole);
    String[] bodies = {
      "{\"allow\":" + BAN_MEMBERS + ",\"deny\":0}",
      "{\"allow\":0,\"deny\":" + BAN_MEMBERS + "}",
      "{\"allow\":" + SEND_MESSAGES + ",\"deny\":" + (SEND_MESSAGES | VIEW_CHANNEL) + "}",
      "{\"allow\":-1,\"deny\":0}",
      "{\"allow\":0,\"deny\":-64}",
      "{\"allow\":" + (1L << 40) + ",\"deny\":0}",
      "{\"allow\":\"x\",\"deny\":0}",
      "{\"allow\":"
    };

    for (String json : bodies) {
      assertBadRequest(raw("PUT", path, f.owner, json));
    }

    assertThat(overwrites.findByChannel(new ChannelId(UUID.fromString(f.announcements)))).isEmpty();
    assertThat(outbox(f.announcements)).isEmpty();
  }

  // Kịch bản: targetType lạ và id hỏng là 400, targetType không phân biệt hoa thường
  // Input: PUT với targetType "user", id đích hỏng, channelId hỏng; rồi "Role" và "MEMBER"
  // Output: ba lần đầu 400 VALIDATION_FAILED; hai lần sau 204
  @Test
  void targetTypeAndIdsAreValidated() throws Exception {
    Fx f = fx();
    String body = "{\"allow\":0,\"deny\":" + SEND_MESSAGES + "}";

    assertBadRequest(raw("PUT", path(f, f.announcements, "user", f.modRole), f.owner, body));
    assertBadRequest(raw("PUT", path(f, f.announcements, "role", "not-a-uuid"), f.owner, body));
    assertBadRequest(raw("PUT", path(f, "not-a-uuid", "role", f.modRole), f.owner, body));
    assertThat(raw("PUT", path(f, f.announcements, "Role", f.modRole), f.owner, body).statusCode())
        .isEqualTo(204);
    assertThat(
            raw("PUT", path(f, f.announcements, "MEMBER", f.mod.toString()), f.owner, body)
                .statusCode())
        .isEqualTo(204);
    assertBadRequest(delete(f, f.owner, f.announcements, "user", f.modRole));
  }

  // Kịch bản: phân quyền người gọi: người lạ 404, thiếu MANAGE_ROLES 403, đủ quyền thì được
  // Input: người lạ, member thường và manager PUT overwrite cho Mod rồi DELETE
  // Output: người lạ 404, member thường 403 (PUT và DELETE), manager 204; không có hàng sai
  @Test
  void callerAuthority() throws Exception {
    Fx f = fx();

    assertThat(
            putRole(f, UUID.randomUUID(), f.announcements, f.modRole, 0, SEND_MESSAGES)
                .statusCode())
        .isEqualTo(404);
    HttpResponse<String> denied = putRole(f, f.plain, f.announcements, f.modRole, 0, SEND_MESSAGES);
    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(MAPPER.readTree(denied.body()).get("code").asText()).isEqualTo("PERMISSION_DENIED");
    assertThat(delete(f, f.plain, f.announcements, "role", f.modRole).statusCode()).isEqualTo(403);
    assertThat(outbox(f.announcements)).isEmpty();

    assertThat(putRole(f, f.manager, f.announcements, f.modRole, 0, SEND_MESSAGES).statusCode())
        .isEqualTo(204);
    assertThat(delete(f, f.manager, f.announcements, "role", f.modRole).statusCode())
        .isEqualTo(204);
  }

  // Kịch bản: đích hoặc kênh không tồn tại trong server là 404
  // Input: kênh của server khác, role lạ, user chưa vào server, role của server khác
  // Output: cả bốn PUT trả 404 NOT_FOUND và không có hàng overwrite
  @Test
  void unknownChannelOrTargetIs404() throws Exception {
    Fx f = fx();
    Fx other = fx();

    assertThat(
            put(f, f.owner, other.announcements, "role", f.modRole, 0, SEND_MESSAGES).statusCode())
        .isEqualTo(404);
    assertThat(
            put(f, f.owner, f.announcements, "role", UUID.randomUUID().toString(), 0, SEND_MESSAGES)
                .statusCode())
        .isEqualTo(404);
    assertThat(
            put(
                    f,
                    f.owner,
                    f.announcements,
                    "member",
                    UUID.randomUUID().toString(),
                    0,
                    SEND_MESSAGES)
                .statusCode())
        .isEqualTo(404);
    HttpResponse<String> foreignRole =
        put(f, f.owner, f.announcements, "role", other.modRole, 0, SEND_MESSAGES);
    assertThat(foreignRole.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(foreignRole.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(overwrites.findByChannel(new ChannelId(UUID.fromString(f.announcements)))).isEmpty();
  }

  // Kịch bản: manager không cấp được bit mình không có và không quản được role cao hơn mình
  // Input: manager (vị trí 2) allow MANAGE_MESSAGES, đặt cho Boss (4), cho Mod (1), cho @everyone
  // Output: allow MANAGE_MESSAGES 403; Boss 403; Mod 204; @everyone 204; @everyone allow MANAGE 403
  @Test
  void managerIsBoundByGrantAndHierarchyRules() throws Exception {
    Fx f = fx();
    String boss = role(f, "{\"name\":\"Boss\",\"position\":4}");

    assertThat(putRole(f, f.manager, f.announcements, f.modRole, MANAGE_MESSAGES, 0).statusCode())
        .isEqualTo(403);
    assertThat(putRole(f, f.manager, f.announcements, boss, 0, SEND_MESSAGES).statusCode())
        .isEqualTo(403);
    assertThat(delete(f, f.manager, f.announcements, "role", boss).statusCode()).isEqualTo(403);
    assertThat(putRole(f, f.manager, f.announcements, f.modRole, SEND_MESSAGES, 0).statusCode())
        .isEqualTo(204);
    assertThat(putRole(f, f.manager, f.announcements, f.everyone, 0, SEND_MESSAGES).statusCode())
        .isEqualTo(204);
    assertThat(putRole(f, f.manager, f.announcements, f.everyone, MANAGE_MESSAGES, 0).statusCode())
        .isEqualTo(403);
  }

  // Kịch bản: quyền MANAGE_CHANNELS cấp ở server không vượt qua deny hay ẩn kênh ở cấp kênh
  // Input: Chan có MANAGE_CHANNELS; owner deny MANAGE_CHANNELS cho Chan ở announcements, deny VIEW
  // ở hidden
  // Output: PATCH và DELETE announcements 403, hidden 404, kênh còn đủ; PATCH general 200
  @Test
  void channelOverwritesLimitEditAndDeleteChannel() throws Exception {
    Fx f = fx();
    String chan = role(f, "{\"name\":\"Chan\",\"permissions\":8,\"position\":1}");
    assign(f.server, f.plain, chan);
    String hidden =
        MAPPER
            .readTree(raw("POST", f.server + "/channels", f.owner, "{\"name\":\"hidden\"}").body())
            .get("id")
            .asText();
    putRole(f, f.owner, f.announcements, chan, 0, 8);
    putRole(f, f.owner, hidden, chan, 0, VIEW_CHANNEL);
    String patch = "{\"name\":\"renamed\"}";

    assertThat(raw("PATCH", f.server + "/channels/" + f.announcements, f.plain, patch).statusCode())
        .isEqualTo(403);
    assertThat(raw("DELETE", f.server + "/channels/" + f.announcements, f.plain, null).statusCode())
        .isEqualTo(403);
    assertThat(raw("PATCH", f.server + "/channels/" + hidden, f.plain, patch).statusCode())
        .isEqualTo(404);
    assertThat(raw("DELETE", f.server + "/channels/" + hidden, f.plain, null).statusCode())
        .isEqualTo(404);

    Integer left =
        jdbc.queryForObject(
            "select count(*) from community.channels where id in (?::uuid, ?::uuid)",
            Integer.class,
            f.announcements,
            hidden);
    assertThat(left).isEqualTo(2);
    assertThat(raw("PATCH", f.server + "/channels/" + f.general, f.plain, patch).statusCode())
        .isEqualTo(200);
  }

  // Kịch bản: allow MANAGE_CHANNELS riêng ở một kênh đủ để sửa và xóa kênh đó, không lan sang kênh
  // khác
  // Input: member thường được owner allow MANAGE_CHANNELS ở announcements; PATCH và DELETE hai kênh
  // Output: PATCH và DELETE announcements 200 và 204; PATCH general vẫn 403
  @Test
  void channelLevelAllowManagesOnlyThatChannel() throws Exception {
    Fx f = fx();
    put(f, f.owner, f.announcements, "member", f.plain.toString(), 8, 0);
    String patch = "{\"name\":\"renamed\"}";

    assertThat(raw("PATCH", f.server + "/channels/" + f.general, f.plain, patch).statusCode())
        .isEqualTo(403);
    assertThat(raw("PATCH", f.server + "/channels/" + f.announcements, f.plain, patch).statusCode())
        .isEqualTo(200);
    assertThat(raw("DELETE", f.server + "/channels/" + f.announcements, f.plain, null).statusCode())
        .isEqualTo(204);
  }

  // Kịch bản: xóa role dọn mọi overwrite của role đó
  // Input: overwrite cho Mod ở hai kênh và cho @everyone, rồi DELETE role Mod
  // Output: overwrite của Mod biến mất ở cả hai kênh, overwrite @everyone còn nguyên
  @Test
  void deleteRoleRemovesItsOverwrites() throws Exception {
    Fx f = fx();
    putRole(f, f.owner, f.announcements, f.modRole, SEND_MESSAGES, 0);
    putRole(f, f.owner, f.general, f.modRole, SEND_MESSAGES, 0);
    putRole(f, f.owner, f.announcements, f.everyone, 0, SEND_MESSAGES);

    assertThat(raw("DELETE", f.server + "/roles/" + f.modRole, f.owner, null).statusCode())
        .isEqualTo(204);

    assertThat(overwrites.findByChannel(new ChannelId(UUID.fromString(f.general)))).isEmpty();
    List<ChannelOverwrite> left =
        overwrites.findByChannel(new ChannelId(UUID.fromString(f.announcements)));
    assertThat(left).hasSize(1);
    assertThat(left.get(0).targetId()).isEqualTo(UUID.fromString(f.everyone));
  }

  // Kịch bản: rời server dọn overwrite MEMBER, vào lại không còn overwrite cũ
  // Input: overwrite MEMBER cho plain, plain DELETE members/@me rồi được thêm lại vào server
  // Output: sau khi rời đã không còn hàng; sau khi vào lại quyền SEND ở announcements như mặc định
  @Test
  void leaveAndRejoinDropsMemberOverwrite() throws Exception {
    Fx f = fx();
    put(f, f.owner, f.announcements, "member", f.plain.toString(), 0, SEND_MESSAGES);
    assertThat(perm(f, f.plain, f.announcements) & SEND_MESSAGES).isZero();

    assertThat(raw("DELETE", f.server + "/members/@me", f.plain, null).statusCode()).isEqualTo(204);

    assertThat(overwrites.findByChannel(new ChannelId(UUID.fromString(f.announcements)))).isEmpty();
    addMember(f.server, f.plain);
    assertThat(perm(f, f.plain, f.announcements) & SEND_MESSAGES).isEqualTo(SEND_MESSAGES);
  }

  // Dựng server với kênh general và announcements, ba role Mod, Manager, Admin và năm user
  private Fx fx() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID plain = UUID.randomUUID();
    UUID mod = UUID.randomUUID();
    UUID manager = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    JsonNode created = MAPPER.readTree(raw("POST", "", owner, "{\"name\":\"Overwrites\"}").body());
    String server = created.get("id").asText();
    String general = created.get("channels").get(0).get("id").asText();
    Fx base = new Fx(owner, plain, mod, manager, admin, server, general, null, null, null);
    String announcements =
        MAPPER
            .readTree(
                raw("POST", server + "/channels", owner, "{\"name\":\"announcements\"}").body())
            .get("id")
            .asText();
    String everyone =
        MAPPER
            .readTree(raw("GET", server + "/roles", owner, null).body())
            .get(0)
            .get("id")
            .asText();
    String modRole = role(base, "{\"name\":\"Mod\",\"position\":1}");
    String managerRole =
        role(base, "{\"name\":\"Manager\",\"permissions\":" + MANAGE_ROLES + ",\"position\":2}");
    String adminRole =
        role(base, "{\"name\":\"Admin\",\"permissions\":" + ADMINISTRATOR + ",\"position\":3}");
    for (UUID u : List.of(plain, mod, manager, admin)) {
      addMember(server, u);
    }
    assign(server, mod, modRole);
    assign(server, manager, managerRole);
    assign(server, admin, adminRole);
    return new Fx(
        owner, plain, mod, manager, admin, server, general, announcements, everyone, modRole);
  }

  // Owner cộng MANAGE_ROLES vào quyền @everyone nên mọi member đều có quyền này ở rank 0
  private void grantEveryoneManageRoles(Fx f) throws Exception {
    long current =
        MAPPER
            .readTree(raw("GET", f.server + "/roles", f.owner, null).body())
            .get(0)
            .get("permissions")
            .asLong();
    raw(
        "PATCH",
        f.server + "/roles/" + f.everyone,
        f.owner,
        "{\"permissions\":" + (current | MANAGE_ROLES) + "}");
  }

  // Tạo role qua REST bằng owner, kiểm tra 201 và trả id
  private String role(Fx f, String json) throws Exception {
    HttpResponse<String> response = raw("POST", f.server + "/roles", f.owner, json);
    assertThat(response.statusCode()).isEqualTo(201);
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Thêm thành viên trực tiếp vào bảng members
  private void addMember(String server, UUID user) {
    jdbc.update(
        "insert into community.members (server_id, user_id, joined_at) values (?::uuid, ?, now())",
        server,
        user);
  }

  // Gán role cho thành viên trực tiếp vào bảng member_roles
  private void assign(String server, UUID user, String role) {
    jdbc.update(
        "insert into community.member_roles (server_id, user_id, role_id)"
            + " values (?::uuid, ?, ?::uuid)",
        server,
        user,
        role);
  }

  // Quyền hiệu lực của user trên kênh theo GET permissions/@me, kênh vắng thì thất bại
  private long perm(Fx f, UUID user, String channel) throws Exception {
    Long value = meChannels(f, user).get(channel);
    assertThat(value).as("channel %s in @me", channel).isNotNull();
    return value;
  }

  // Các kênh trong GET permissions/@me của user, channelId sang quyền
  private Map<String, Long> meChannels(Fx f, UUID user) throws Exception {
    JsonNode body = MAPPER.readTree(raw("GET", f.server + "/permissions/@me", user, null).body());
    Map<String, Long> out = new HashMap<>();
    body.get("channels")
        .forEach(c -> out.put(c.get("channelId").asText(), c.get("permissions").asLong()));
    return out;
  }

  // Id các kênh user thấy trong GET server
  private List<String> channelIds(Fx f, UUID user) throws Exception {
    JsonNode body = MAPPER.readTree(raw("GET", f.server, user, null).body());
    List<String> ids = new ArrayList<>();
    body.get("channels").forEach(c -> ids.add(c.get("id").asText()));
    return ids;
  }

  // Payload outbox channel.permissions-changed của một kênh, cũ trước mới sau
  private List<String> outbox(String channel) {
    return jdbc.queryForList(
        "select payload::text from community.outbox_event where routing_key ="
            + " 'channel.permissions-changed' and aggregate_id = ?::uuid order by created_at",
        String.class,
        channel);
  }

  // Kiểm tra phản hồi là 400 VALIDATION_FAILED
  private static void assertBadRequest(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(response.body()).get("code").asText())
        .isEqualTo("VALIDATION_FAILED");
  }

  // Đường dẫn overwrite tính từ /api/v1/servers
  private static String path(Fx f, String channel, String type, String target) {
    return f.server + "/channels/" + channel + "/permissions/" + type + "/" + target;
  }

  // PUT overwrite cho role
  private HttpResponse<String> putRole(
      Fx f, UUID actor, String channel, String role, long allow, long deny) throws Exception {
    return put(f, actor, channel, "role", role, allow, deny);
  }

  // PUT overwrite với loại và id đích tùy ý
  private HttpResponse<String> put(
      Fx f, UUID actor, String channel, String type, String target, long allow, long deny)
      throws Exception {
    return raw(
        "PUT",
        path(f, channel, type, target),
        actor,
        "{\"allow\":" + allow + ",\"deny\":" + deny + "}");
  }

  // DELETE overwrite
  private HttpResponse<String> delete(Fx f, UUID actor, String channel, String type, String target)
      throws Exception {
    return raw("DELETE", path(f, channel, type, target), actor, null);
  }

  // Gọi method trên /api/v1/servers/{path} với Bearer của user, body null là không gửi body
  private HttpResponse<String> raw(String method, String path, UUID user, String json)
      throws Exception {
    String suffix = path.isEmpty() ? "" : "/" + path;
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/servers" + suffix))
            .header("Authorization", bearer(user));
    if (json != null) {
      builder.header("Content-Type", "application/json");
    }
    return HttpClient.newHttpClient()
        .send(
            builder
                .method(
                    method,
                    json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
