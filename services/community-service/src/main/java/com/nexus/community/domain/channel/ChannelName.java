package com.nexus.community.domain.channel;

import com.nexus.community.domain.shared.DomainValidationException;
import java.util.regex.Pattern;

// Tên kênh gồm a-z, 0-9 và dấu gạch ngang, dài 1 đến 100, không tự chuẩn hóa
public record ChannelName(String value) {

  private static final Pattern FORMAT = Pattern.compile("[a-z0-9-]{1,100}");

  // Kiểm tra định dạng, null hoặc sai định dạng thì ném lỗi
  public ChannelName {
    if (value == null) {
      throw new DomainValidationException("channel name is required");
    }
    if (!FORMAT.matcher(value).matches()) {
      throw new DomainValidationException("channel name is invalid");
    }
  }
}
