package com.nexus.community.domain.invite;

import com.nexus.community.domain.shared.DomainValidationException;
import java.util.regex.Pattern;

// Mã mời gồm đúng 8 ký tự chữ và số
public record InviteCode(String value) {

  private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9]{8}");

  // Kiểm tra định dạng, null hoặc sai định dạng thì ném lỗi
  public InviteCode {
    if (!isWellFormed(value)) {
      throw new DomainValidationException("invite code is invalid");
    }
  }

  // Cho biết chuỗi có đúng định dạng mã mời không
  public static boolean isWellFormed(String text) {
    return text != null && FORMAT.matcher(text).matches();
  }
}
