package com.nexus.community.application.port;

import com.nexus.community.domain.invite.InviteCode;

// Cổng sinh mã mời ngẫu nhiên
public interface InviteCodeGenerator {

  // Sinh một mã mời mới
  InviteCode generate();
}
