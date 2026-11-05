package com.nexus.identity.application.port;

import com.nexus.identity.domain.user.User;

// Cổng phát access token cho user đã xác thực
public interface TokenIssuer {

  // Phát access token ngắn hạn cho user
  AccessToken issue(User user);
}
