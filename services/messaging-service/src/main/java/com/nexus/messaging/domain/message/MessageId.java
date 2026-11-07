package com.nexus.messaging.domain.message;

import com.github.f4b6a3.uuid.UuidCreator;
import com.nexus.messaging.domain.shared.DomainValidationException;
import java.util.UUID;

// Định danh tin nhắn dạng UUIDv7 đơn điệu để phân trang keyset theo id
public record MessageId(UUID value) {

  // Từ chối giá trị rỗng
  public MessageId {
    if (value == null) {
      throw new DomainValidationException("messageId is required");
    }
  }

  // Sinh định danh mới tăng đơn điệu kể cả trong cùng một mili giây
  public static MessageId newId() {
    return new MessageId(UuidCreator.getTimeOrderedEpochPlus1());
  }
}
