package com.nexus.messaging.domain.reaction;

import com.nexus.messaging.domain.shared.DomainValidationException;

// Giá trị emoji 1 đến 32 ký tự, chỉ gồm emoji, ZWJ, VS16 và keycap, phải có phần hiển thị như emoji
public record Emoji(String value) {

  private static final int MAX_CODE_POINTS = 32;
  private static final int ZWJ = 0x200D;
  private static final int VS16 = 0xFE0F;
  private static final int KEYCAP = 0x20E3;

  // Từ chối chuỗi rỗng, quá dài hoặc chứa ký tự không phải emoji
  public Emoji {
    if (value == null
        || value.isEmpty()
        || value.codePointCount(0, value.length()) > MAX_CODE_POINTS) {
      throw new DomainValidationException("emoji must be 1 to 32 characters");
    }
    boolean displayed = false;
    int[] cps = value.codePoints().toArray();
    for (int i = 0; i < cps.length; i++) {
      int cp = cps[i];
      boolean modifier = cp == ZWJ || cp == VS16 || cp == KEYCAP;
      if (!modifier && !Character.isEmoji(cp)) {
        throw new DomainValidationException("emoji contains a non-emoji character");
      }
      displayed |= Character.isEmojiPresentation(cp) || (i > 0 && (cp == VS16 || cp == KEYCAP));
    }
    if (!displayed) {
      throw new DomainValidationException("emoji is not a displayable emoji");
    }
  }
}
