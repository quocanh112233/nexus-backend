package com.nexus.messaging.domain.social;

import com.github.f4b6a3.uuid.UuidCreator;
import com.nexus.messaging.domain.shared.DomainValidationException;
import java.util.UUID;

// Định danh lời mời kết bạn dạng UUIDv7
public record FriendRequestId(UUID value) {

  // Từ chối giá trị rỗng
  public FriendRequestId {
    if (value == null) {
      throw new DomainValidationException("friendRequestId is required");
    }
  }

  // Sinh định danh mới tăng đơn điệu
  public static FriendRequestId newId() {
    return new FriendRequestId(UuidCreator.getTimeOrderedEpochPlus1());
  }
}
