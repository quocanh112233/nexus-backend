package com.nexus.identity.domain.token;

import com.nexus.identity.domain.shared.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

// Chuỗi băm SHA-256 dạng 64 ký tự hex chữ thường của refresh token
public record TokenHash(String value) {

  private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

  // Từ chối giá trị không phải 64 ký tự hex chữ thường
  public TokenHash {
    if (value == null || !HEX64.matcher(value).matches()) {
      throw new DomainValidationException("tokenHash must be 64 lowercase hex chars");
    }
  }

  // Băm chuỗi thô bằng SHA-256 rồi mã hóa hex
  public static TokenHash ofRaw(String raw) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return new TokenHash(HexFormat.of().formatHex(digest));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }
}
