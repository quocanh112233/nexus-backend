package com.nexus.identity.application.port;

import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.RawPassword;

// Cổng băm và đối chiếu mật khẩu
public interface PasswordHasher {

  // Băm mật khẩu thô thành chuỗi băm có salt
  PasswordHash hash(RawPassword raw);

  // Đối chiếu mật khẩu thô với chuỗi băm đã lưu
  boolean matches(RawPassword raw, PasswordHash hash);
}
