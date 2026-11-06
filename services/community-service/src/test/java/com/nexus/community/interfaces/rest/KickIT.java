package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.nexus.community.domain.member.MemberRepository;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

// Kiểm tra kick thành viên qua REST: thứ bậc, phân quyền, dọn overwrite, vào lại và outbox
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KickIT extends ModerationTestBase {

  @MockitoSpyBean private MemberRepository memberRepository;

  // Kịch bản: Mod kick member thường, member mất khỏi danh sách và phát MemberKicked
  // Input: Mod DELETE members/{plain}, rồi kick lại lần nữa
  // Output: 204, GET members không còn plain, outbox một member.kicked đủ ba id; lần hai 404
  @Test
  void modKicksPlainMember() throws Exception {
    Fx f = fx();

    HttpResponse<String> response = call("DELETE", f.server + "/members/" + f.plain, f.mod, null);

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(memberIds(f)).doesNotContain(f.plain.toString());
    assertThat(outbox(f, "member.kicked")).hasSize(1);
    JsonNode event = outbox(f, "member.kicked").get(0);
    assertThat(event.get("serverId").asText()).isEqualTo(f.server);
    assertThat(event.get("userId").asText()).isEqualTo(f.plain.toString());
    assertThat(event.get("kickedBy").asText()).isEqualTo(f.mod.toString());
    HttpResponse<String> again = call("DELETE", f.server + "/members/" + f.plain, f.mod, null);
    assertThat(again.statusCode()).isEqualTo(404);
    assertThat(code(again)).isEqualTo("NOT_FOUND");
  }

  // Kịch bản: kick người cao hơn, ngang cấp, chủ server hoặc chính mình đều bị chặn
  // Input: Mod kick Admin (3), Mod2 (ngang cấp), owner và chính Mod
  // Output: cả bốn 403 PERMISSION_DENIED, không ai bị gỡ và outbox không có member.kicked
  @Test
  void higherEqualOwnerAndSelfAreForbidden() throws Exception {
    Fx f = fx();

    for (UUID target : new UUID[] {f.admin, f.mod2, f.owner, f.mod}) {
      HttpResponse<String> response = call("DELETE", f.server + "/members/" + target, f.mod, null);
      assertThat(response.statusCode()).as(target.toString()).isEqualTo(403);
      assertThat(code(response)).isEqualTo("PERMISSION_DENIED");
    }
    assertThat(memberIds(f))
        .contains(f.admin.toString(), f.mod2.toString(), f.owner.toString(), f.mod.toString());
    assertThat(outbox(f, "member.kicked")).isEmpty();
  }

  // Kịch bản: chủ server kick được người cao cấp nhất nhưng không kick được chính mình
  // Input: owner kick Admin, rồi owner kick owner
  // Output: 204 cho Admin, 403 cho chính owner
  @Test
  void ownerKicksAdminButNotSelf() throws Exception {
    Fx f = fx();

    assertThat(call("DELETE", f.server + "/members/" + f.admin, f.owner, null).statusCode())
        .isEqualTo(204);
    assertThat(call("DELETE", f.server + "/members/" + f.owner, f.owner, null).statusCode())
        .isEqualTo(403);
  }

  // Kịch bản: thiếu KICK_MEMBERS thì 403, người ngoài 404, userId hỏng 400
  // Input: member thường và người lạ kick Mod, Mod kick not-a-uuid
  // Output: 403 PERMISSION_DENIED, 404 NOT_FOUND, 400 VALIDATION_FAILED và Mod vẫn còn
  @Test
  void permissionStrangerAndMalformed() throws Exception {
    Fx f = fx();

    HttpResponse<String> denied = call("DELETE", f.server + "/members/" + f.mod, f.plain, null);
    HttpResponse<String> hidden =
        call("DELETE", f.server + "/members/" + f.mod, UUID.randomUUID(), null);
    HttpResponse<String> bad = call("DELETE", f.server + "/members/not-a-uuid", f.mod, null);

    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(code(denied)).isEqualTo("PERMISSION_DENIED");
    assertThat(hidden.statusCode()).isEqualTo(404);
    assertThat(bad.statusCode()).isEqualTo(400);
    assertThat(code(bad)).isEqualTo("VALIDATION_FAILED");
    assertThat(memberIds(f)).contains(f.mod.toString());
  }

  // Kịch bản: người bị kick vào lại bằng lời mời và không giữ role cũ
  // Input: owner kick Mod2 (giữ role Mod), Mod2 accept lời mời cũ
  // Output: accept 200, GET members thấy Mod2 với roleIds rỗng
  @Test
  void kickedUserRejoinsWithoutRoles() throws Exception {
    Fx f = fx();
    String code = invite(f);

    assertThat(call("DELETE", f.server + "/members/" + f.mod2, f.owner, null).statusCode())
        .isEqualTo(204);
    assertThat(accept(code, f.mod2).statusCode()).isEqualTo(200);

    JsonNode page =
        MAPPER.readTree(call("GET", f.server + "/members?limit=100", f.owner, null).body());
    boolean found = false;
    for (JsonNode m : page.get("items")) {
      if (m.get("userId").asText().equals(f.mod2.toString())) {
        found = true;
        assertThat(m.get("roleIds")).isEmpty();
      }
    }
    assertThat(found).isTrue();
  }

  // Kịch bản: DELETE members/@me vẫn là rời server, không bị nhầm với kick theo userId
  // Input: member thường DELETE members/@me
  // Output: 204 và member thường không còn trong danh sách
  @Test
  void leaveStillWorks() throws Exception {
    Fx f = fx();

    assertThat(call("DELETE", f.server + "/members/@me", f.plain, null).statusCode())
        .isEqualTo(204);

    assertThat(memberIds(f)).doesNotContain(f.plain.toString());
  }

  // Kịch bản: kick dọn overwrite MEMBER nên vào lại không còn overwrite cũ
  // Input: owner đặt overwrite MEMBER cho plain, Mod kick plain
  // Output: số overwrite MEMBER của plain từ 1 về 0
  @Test
  void kickRemovesMemberOverwrites() throws Exception {
    Fx f = fx();
    overwriteFor(f, f.plain);
    assertThat(memberOverwrites(f, f.plain)).isEqualTo(1);

    assertThat(call("DELETE", f.server + "/members/" + f.plain, f.mod, null).statusCode())
        .isEqualTo(204);

    assertThat(memberOverwrites(f, f.plain)).isZero();
  }

  // Kịch bản: KICK_MEMBERS cấp qua @everyone không cho member rank 0 kick member rank 0 khác
  // Input: owner cấp KICK_MEMBERS cho @everyone, plain kick một member thường khác
  // Output: 403 PERMISSION_DENIED và người bị kick vẫn còn trong danh sách
  @Test
  void everyoneKickGrantCannotKickEqualRank() throws Exception {
    Fx f = fx();
    UUID other = UUID.randomUUID();
    addMember(f.server, other);
    grantEveryone(f, 2048);

    HttpResponse<String> response = call("DELETE", f.server + "/members/" + other, f.plain, null);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("PERMISSION_DENIED");
    assertThat(memberIds(f)).contains(other.toString());
  }

  // Kịch bản: Mod kick đúng lúc owner nâng member lên Admin, kick phải thấy role mới và bị chặn
  // Input: owner PUT roleIds [Admin] cho plain, dừng sau khi khóa hàng; Mod kick plain lúc đó
  // Output: kick 403 vì rank đã là Admin, plain còn là member với role Admin
  @Test
  void kickSeesRoleAssignedConcurrently() throws Exception {
    Fx f = fx();
    String admin = roleId(f, "Admin");
    CountDownLatch locked = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean();
    doAnswer(
            invocation -> {
              Object read = invocation.callRealMethod();
              if (first.compareAndSet(false, true)) {
                locked.countDown();
                Thread.sleep(1500);
              }
              return read;
            })
        .when(memberRepository)
        .findForUpdate(any(), any());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<HttpResponse<String>> assign =
          pool.submit(
              () ->
                  call(
                      "PUT",
                      f.server + "/members/" + f.plain + "/roles",
                      f.owner,
                      "{\"roleIds\":[\"" + admin + "\"]}"));
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

      HttpResponse<String> kick = call("DELETE", f.server + "/members/" + f.plain, f.mod, null);

      assertThat(assign.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(kick.statusCode()).isEqualTo(403);
    } finally {
      pool.shutdownNow();
    }
    assertThat(memberIds(f)).contains(f.plain.toString());
  }
}
