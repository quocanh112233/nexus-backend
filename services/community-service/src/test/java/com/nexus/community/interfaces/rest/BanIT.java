package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

// Kiểm tra ban, gỡ ban và danh sách ban qua REST: thứ bậc, phân quyền, phân trang, vào lại, outbox
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BanIT extends ModerationTestBase {

  // Kịch bản: Mod ban member thường, member bị gỡ, không vào lại được và phát MemberBanned
  // Input: Mod PUT bans/{plain} kèm lý do, rồi plain accept lời mời
  // Output: 204, GET members không còn plain, accept 403 BANNED_FROM_SERVER, outbox wasMember true
  @Test
  void banMemberBlocksRejoin() throws Exception {
    Fx f = fx();
    String code = invite(f);

    HttpResponse<String> response = ban(f, f.mod, f.plain, "{\"reason\":\"spam\"}");

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(memberIds(f)).doesNotContain(f.plain.toString());
    HttpResponse<String> rejoin = accept(code, f.plain);
    assertThat(rejoin.statusCode()).isEqualTo(403);
    assertThat(code(rejoin)).isEqualTo("BANNED_FROM_SERVER");
    JsonNode event = outbox(f, "member.banned").get(0);
    assertThat(event.get("userId").asText()).isEqualTo(f.plain.toString());
    assertThat(event.get("bannedBy").asText()).isEqualTo(f.mod.toString());
    assertThat(event.get("reason").asText()).isEqualTo("spam");
    assertThat(event.get("wasMember").asBoolean()).isTrue();
  }

  // Kịch bản: ban lần hai cùng người là no-op, không thêm dòng hay sự kiện
  // Input: Mod PUT bans/{plain} hai lần
  // Output: cả hai 204, đúng một dòng server_bans và một member.banned
  @Test
  void banTwiceIsIdempotent() throws Exception {
    Fx f = fx();

    assertThat(ban(f, f.mod, f.plain, null).statusCode()).isEqualTo(204);
    assertThat(ban(f, f.mod, f.plain, "{\"reason\":\"again\"}").statusCode()).isEqualTo(204);

    assertThat(banRows(f)).isEqualTo(1);
    assertThat(outbox(f, "member.banned")).hasSize(1);
  }

  // Kịch bản: ban người chưa là member vẫn được và chặn họ vào sau
  // Input: Mod PUT bans/{người lạ} không body, rồi người lạ accept
  // Output: 204, outbox wasMember false reason null, accept 403 BANNED_FROM_SERVER
  @Test
  void banNonMember() throws Exception {
    Fx f = fx();
    UUID stranger = UUID.randomUUID();

    assertThat(ban(f, f.mod, stranger, null).statusCode()).isEqualTo(204);

    JsonNode event = outbox(f, "member.banned").get(0);
    assertThat(event.get("wasMember").asBoolean()).isFalse();
    assertThat(event.get("reason").isNull()).isTrue();
    assertThat(code(accept(invite(f), stranger))).isEqualTo("BANNED_FROM_SERVER");
  }

  // Kịch bản: lý do dài quá 512 ký tự bị từ chối, đúng 512 thì nhận
  // Input: Mod ban plain với reason 513 ký tự, rồi với reason 512 ký tự
  // Output: lần đầu 400 VALIDATION_FAILED và chưa có ban; lần hai 204
  @Test
  void reasonLimit() throws Exception {
    Fx f = fx();

    HttpResponse<String> tooLong =
        ban(f, f.mod, f.plain, "{\"reason\":\"" + "x".repeat(513) + "\"}");

    assertThat(tooLong.statusCode()).isEqualTo(400);
    assertThat(code(tooLong)).isEqualTo("VALIDATION_FAILED");
    assertThat(banRows(f)).isZero();
    assertThat(ban(f, f.mod, f.plain, "{\"reason\":\"" + "x".repeat(512) + "\"}").statusCode())
        .isEqualTo(204);
  }

  // Kịch bản: ban chủ server, chính mình, người cao hơn hoặc ngang cấp đều bị chặn
  // Input: Mod ban owner, Mod, Admin, Mod2; owner ban chính owner
  // Output: cả năm 403 PERMISSION_DENIED, không có dòng ban và không ai bị gỡ
  @Test
  void ownerSelfHigherEqualAreForbidden() throws Exception {
    Fx f = fx();

    for (UUID target : new UUID[] {f.owner, f.mod, f.admin, f.mod2}) {
      HttpResponse<String> response = ban(f, f.mod, target, null);
      assertThat(response.statusCode()).as(target.toString()).isEqualTo(403);
      assertThat(code(response)).isEqualTo("PERMISSION_DENIED");
    }
    assertThat(ban(f, f.owner, f.owner, null).statusCode()).isEqualTo(403);
    assertThat(banRows(f)).isZero();
    assertThat(memberIds(f)).hasSize(5);
  }

  // Kịch bản: GET bans trả đủ trường và phân trang theo con trỏ
  // Input: Mod ban ba người lạ kèm lý do, GET limit=2 rồi GET theo nextCursor
  // Output: trang một hai mục có nextCursor, trang hai một mục nextCursor null, đủ trường
  @Test
  void listBansPaginates() throws Exception {
    Fx f = fx();
    List<String> targets = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      UUID target = UUID.randomUUID();
      targets.add(target.toString());
      ban(f, f.mod, target, "{\"reason\":\"r" + i + "\"}");
    }
    targets.sort(String::compareTo);

    JsonNode first = MAPPER.readTree(call("GET", f.server + "/bans?limit=2", f.mod, null).body());
    String cursor = first.get("nextCursor").asText();
    JsonNode second =
        MAPPER.readTree(
            call("GET", f.server + "/bans?limit=2&cursor=" + cursor, f.mod, null).body());

    assertThat(first.get("items")).hasSize(2);
    assertThat(first.get("items").get(0).get("userId").asText()).isEqualTo(targets.get(0));
    assertThat(first.get("items").get(1).get("userId").asText()).isEqualTo(targets.get(1));
    JsonNode item = first.get("items").get(0);
    assertThat(item.get("bannedBy").asText()).isEqualTo(f.mod.toString());
    assertThat(item.get("reason").asText()).startsWith("r");
    assertThat(item.get("createdAt").asText()).isNotBlank();
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("items").get(0).get("userId").asText()).isEqualTo(targets.get(2));
    assertThat(second.get("nextCursor").isNull()).isTrue();
  }

  // Kịch bản: limit ngoài 1..100 hoặc cursor hỏng bị từ chối
  // Input: GET bans limit=0, limit=101, cursor=%%%
  // Output: cả ba 400 VALIDATION_FAILED
  @Test
  void badPagingIs400() throws Exception {
    Fx f = fx();

    for (String query : new String[] {"limit=0", "limit=101", "cursor=%25%25%25"}) {
      HttpResponse<String> response = call("GET", f.server + "/bans?" + query, f.mod, null);
      assertThat(response.statusCode()).as(query).isEqualTo(400);
      assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
    }
  }

  // Kịch bản: gỡ ban cho phép vào lại, gỡ người không bị ban là 404
  // Input: Mod ban plain, unban, GET bans, plain accept; rồi unban lần nữa
  // Output: unban 204, danh sách rỗng, accept 200, một member.unbanned; unban lần hai 404
  @Test
  void unbanAllowsRejoin() throws Exception {
    Fx f = fx();
    String code = invite(f);
    ban(f, f.mod, f.plain, null);

    assertThat(call("DELETE", f.server + "/bans/" + f.plain, f.mod, null).statusCode())
        .isEqualTo(204);

    assertThat(MAPPER.readTree(call("GET", f.server + "/bans", f.mod, null).body()).get("items"))
        .isEmpty();
    assertThat(accept(code, f.plain).statusCode()).isEqualTo(200);
    assertThat(outbox(f, "member.unbanned")).hasSize(1);
    HttpResponse<String> again = call("DELETE", f.server + "/bans/" + f.plain, f.mod, null);
    assertThat(again.statusCode()).isEqualTo(404);
    assertThat(code(again)).isEqualTo("NOT_FOUND");
    assertThat(outbox(f, "member.unbanned")).hasSize(1);
  }

  // Kịch bản: mọi endpoint ban thiếu BAN_MEMBERS thì 403, người ngoài 404, userId hỏng 400
  // Input: member thường và người lạ gọi PUT, DELETE, GET bans; Mod PUT bans/not-a-uuid
  // Output: member thường 403, người lạ 404, id hỏng 400, không có dòng ban nào
  @Test
  void permissionAndStranger() throws Exception {
    Fx f = fx();
    String target = "/bans/" + f.mod2;

    for (String[] r : new String[][] {{"PUT", target}, {"DELETE", target}, {"GET", "/bans"}}) {
      assertThat(call(r[0], f.server + r[1], f.plain, null).statusCode()).as(r[0]).isEqualTo(403);
      assertThat(call(r[0], f.server + r[1], UUID.randomUUID(), null).statusCode())
          .as(r[0])
          .isEqualTo(404);
    }
    assertThat(call("PUT", f.server + "/bans/not-a-uuid", f.mod, null).statusCode()).isEqualTo(400);
    assertThat(banRows(f)).isZero();
  }

  // Kịch bản: ban dọn overwrite MEMBER của người bị ban
  // Input: owner đặt overwrite MEMBER cho plain, Mod ban plain
  // Output: số overwrite MEMBER của plain từ 1 về 0
  @Test
  void banRemovesMemberOverwrites() throws Exception {
    Fx f = fx();
    overwriteFor(f, f.plain);
    assertThat(memberOverwrites(f, f.plain)).isEqualTo(1);

    assertThat(ban(f, f.mod, f.plain, null).statusCode()).isEqualTo(204);

    assertThat(memberOverwrites(f, f.plain)).isZero();
  }

  // PUT ban với body tùy chọn, null là không gửi body
  private HttpResponse<String> ban(Fx f, UUID actor, UUID target, String json) throws Exception {
    return call("PUT", f.server + "/bans/" + target, actor, json);
  }

  // Số dòng server_bans của server
  private int banRows(Fx f) {
    return jdbc.queryForObject(
        "select count(*) from community.server_bans where server_id = ?::uuid",
        Integer.class,
        f.server);
  }

  // Kịch bản: BAN_MEMBERS cấp qua @everyone không cho rank 0 ban rank 0, vẫn ban được người ngoài
  // Input: owner cấp BAN_MEMBERS cho @everyone, plain ban member thường khác rồi ban người lạ
  // Output: ban member thường 403 và không có dòng ban; ban người lạ 204 với một dòng ban
  @Test
  void everyoneBanGrantCannotBanEqualRank() throws Exception {
    Fx f = fx();
    UUID other = UUID.randomUUID();
    addMember(f.server, other);
    grantEveryone(f, 4096);

    HttpResponse<String> denied = ban(f, f.plain, other, null);

    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(banRows(f)).isZero();
    assertThat(memberIds(f)).contains(other.toString());
    assertThat(ban(f, f.plain, UUID.randomUUID(), null).statusCode()).isEqualTo(204);
    assertThat(banRows(f)).isEqualTo(1);
  }
}
