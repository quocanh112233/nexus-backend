package com.nexus.community.interfaces.rest;

import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

// Tham số phân trang keyset theo userId dùng chung: limit 1..100 mặc định 50, con trỏ base64url
final class Cursors {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;

  // Lớp tiện ích tĩnh, không tạo thể hiện
  private Cursors() {}

  // Trả limit đã kiểm tra, vắng thì mặc định, ngoài 1..100 thì 400
  static int limit(Integer limit) {
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw new DomainValidationException("limit must be between 1 and " + MAX_LIMIT);
    }
    return size;
  }

  // Giải mã con trỏ base64url thành userId, null là trang đầu, sai định dạng thì 400
  static UserId decode(String cursor) {
    if (cursor == null) {
      return null;
    }
    try {
      return UserId.of(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException | DomainValidationException e) {
      throw new DomainValidationException("cursor is malformed");
    }
  }

  // Mã hóa userId cuối trang thành con trỏ base64url không padding, null thì không có trang sau
  static String encode(UUID lastUserId) {
    return lastUserId == null
        ? null
        : Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(lastUserId.toString().getBytes(StandardCharsets.UTF_8));
  }
}
