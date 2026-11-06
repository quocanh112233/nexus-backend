package com.nexus.community.infrastructure.security;

import com.nexus.community.application.port.InviteCodeGenerator;
import com.nexus.community.domain.invite.InviteCode;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

// Sinh mã mời 8 ký tự [A-Za-z0-9] bằng SecureRandom
@Component
class SecureRandomInviteCodeGenerator implements InviteCodeGenerator {

  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  private final SecureRandom random = new SecureRandom();

  // Chọn ngẫu nhiên đều 8 ký tự từ bảng chữ
  @Override
  public InviteCode generate() {
    StringBuilder code = new StringBuilder(8);
    for (int i = 0; i < 8; i++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return new InviteCode(code.toString());
  }
}
