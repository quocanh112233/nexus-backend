package com.nexus.messaging.domain.message;

import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Tách mention dạng <@uuid> khỏi nội dung tin nhắn
public final class MentionParser {

  private static final Pattern MENTION = Pattern.compile("<@([0-9a-fA-F-]{36})>");
  private static final int MAX_MENTIONS = 20;

  // Không cho khởi tạo
  private MentionParser() {}

  // Trả tập người được nhắc theo thứ tự xuất hiện, bỏ id rác và trùng, quá 20 người thì ném lỗi
  public static Set<UserId> parse(String content) {
    Set<UserId> result = new LinkedHashSet<>();
    Matcher matcher = MENTION.matcher(content);
    while (matcher.find()) {
      UUID id = parseUuid(matcher.group(1));
      if (id != null) {
        result.add(new UserId(id));
      }
    }
    if (result.size() > MAX_MENTIONS) {
      throw new DomainValidationException("at most 20 distinct mentions are allowed");
    }
    return result;
  }

  // Chỉ nhận UUID chuẩn 8-4-4-4-12, UUID.fromString một mình còn chấp nhận dạng rút gọn
  private static UUID parseUuid(String text) {
    try {
      UUID id = UUID.fromString(text);
      return id.toString().equalsIgnoreCase(text) ? id : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
