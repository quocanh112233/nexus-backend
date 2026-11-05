package com.nexus.identity.domain.user;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

// Định danh người dùng dạng UUIDv7
public record UserId(UUID value) {

  // Sinh định danh mới theo thời gian
  public static UserId newId() {
    return new UserId(UuidCreator.getTimeOrderedEpoch());
  }
}
