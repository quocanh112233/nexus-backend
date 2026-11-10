package com.nexus.messaging.domain.social;

import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;

// Cặp hai người dùng khác nhau, low nhỏ hơn high theo thứ tự uuid của PostgreSQL
public record UserPair(UserId low, UserId high) {

  // Bắt buộc low nhỏ hơn hẳn high để constructor không vượt được bất biến
  public UserPair {
    if (low == null || high == null || compare(low, high) >= 0) {
      throw new DomainValidationException("a pair needs two different users with low < high");
    }
  }

  // Chuẩn hóa thứ tự cặp; hai người trùng nhau thì ném lỗi miền
  public static UserPair of(UserId a, UserId b) {
    if (a.equals(b)) {
      throw new DomainValidationException("a pair needs two different users");
    }
    return compare(a, b) < 0 ? new UserPair(a, b) : new UserPair(b, a);
  }

  // So byte không dấu như uuid của PostgreSQL, khác UUID.compareTo so long có dấu
  private static int compare(UserId a, UserId b) {
    int msb =
        Long.compareUnsigned(
            a.value().getMostSignificantBits(), b.value().getMostSignificantBits());
    return msb != 0
        ? msb
        : Long.compareUnsigned(
            a.value().getLeastSignificantBits(), b.value().getLeastSignificantBits());
  }

  // Người dùng có thuộc cặp không
  public boolean contains(UserId user) {
    return low.equals(user) || high.equals(user);
  }

  // Người còn lại trong cặp, user không thuộc cặp thì ném lỗi miền
  public UserId other(UserId user) {
    if (low.equals(user)) {
      return high;
    }
    if (high.equals(user)) {
      return low;
    }
    throw new DomainValidationException("user is not part of the pair");
  }
}
