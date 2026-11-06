package com.nexus.community.application.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.community.application.port.InviteCodeGenerator;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.invite.Invite;
import com.nexus.community.domain.invite.InviteCode;
import com.nexus.community.domain.invite.InviteRepository;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

// Kiểm tra CreateInviteUseCase thử lại mã khi trùng ở bước kiểm tra hoặc khi INSERT
class CreateInviteUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-11-05T01:02:03.123456Z");

  private final ServerAccess access = mock(ServerAccess.class);
  private final InviteRepository invites = mock(InviteRepository.class);
  private final InviteCodeGenerator generator = mock(InviteCodeGenerator.class);
  private final CreateInviteUseCase useCase =
      new CreateInviteUseCase(access, invites, generator, Clock.fixed(NOW, ZoneOffset.UTC));
  private final InviteCode first = new InviteCode("Aaaa1111");
  private final InviteCode second = new InviteCode("Bbbb2222");

  // Kịch bản: INSERT đầu tiên vi phạm khóa chính vì mã vừa bị người khác lấy
  // Input: generate trả hai mã, add mã đầu ném DataIntegrityViolationException
  // Output: trả lời mời mang mã thứ hai, add được gọi hai lần
  @Test
  void retriesWithNewCodeWhenInsertCollides() {
    when(generator.generate()).thenReturn(first, second);
    when(invites.findByCode(any())).thenReturn(Optional.empty());
    doThrow(new DataIntegrityViolationException("dup")).doNothing().when(invites).add(any());

    InviteView view = useCase.execute(new ServerId(UUID.randomUUID()), UserId.newId(), 1, 60);

    assertThat(view.code()).isEqualTo(second.value());
    verify(invites, times(2)).add(any(Invite.class));
  }

  // Kịch bản: mã nào cũng trùng thì dừng sau 3 lần
  // Input: mọi mã đều đã có trong repo
  // Output: IllegalStateException, add không được gọi
  @Test
  void givesUpAfterThreeCollisions() {
    when(generator.generate()).thenReturn(first);
    when(invites.findByCode(any())).thenReturn(Optional.of(mock(Invite.class)));

    assertThatThrownBy(
            () -> useCase.execute(new ServerId(UUID.randomUUID()), UserId.newId(), 1, 60))
        .isInstanceOf(IllegalStateException.class);

    verify(generator, times(3)).generate();
    verify(invites, times(0)).add(any());
  }
}
