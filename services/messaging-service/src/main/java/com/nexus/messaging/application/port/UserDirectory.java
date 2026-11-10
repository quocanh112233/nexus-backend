package com.nexus.messaging.application.port;

import com.nexus.messaging.domain.shared.UserId;

// Cổng tra cứu user ở Identity, ném DependencyUnavailableException khi Identity lỗi hoặc quá hạn
public interface UserDirectory {

  // User có tồn tại không; Identity báo NOT_FOUND thì false
  boolean exists(UserId id);
}
