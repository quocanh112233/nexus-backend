package com.nexus.identity.infrastructure.security;

import com.nexus.identity.application.port.PasswordHasher;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.RawPassword;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

// Băm mật khẩu bằng BCrypt cost 12
@Component
class BcryptPasswordHasher implements PasswordHasher {

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  // Băm mật khẩu thô với salt ngẫu nhiên
  @Override
  public PasswordHash hash(RawPassword raw) {
    return new PasswordHash(encoder.encode(raw.value()));
  }

  // Đối chiếu mật khẩu thô với chuỗi băm
  @Override
  public boolean matches(RawPassword raw, PasswordHash hash) {
    return encoder.matches(raw.value(), hash.value());
  }
}
