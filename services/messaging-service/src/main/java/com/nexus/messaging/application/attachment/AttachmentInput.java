package com.nexus.messaging.application.attachment;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;

// Tệp client đã upload lên kho lưu trữ và muốn đính vào tin
public record AttachmentInput(String publicId, String resourceType, String originalFilename) {

  // Từ chối originalFilename chứa NUL vì Postgres không lưu được
  public AttachmentInput {
    if (originalFilename != null && originalFilename.indexOf('\0') >= 0) {
      throw new DomainValidationException("originalFilename must not contain NUL");
    }
  }

  // Thư mục upload của một user trong một kênh
  public static String folder(String root, ChannelId channel, UserId user) {
    return root + "/attachments/" + channel.value() + "/" + user.value();
  }

  // publicId dưới prefix và mọi đoạn sau không rỗng, dot, "\\", điều khiển, khoảng trắng biên
  public static boolean isUnder(String publicId, String prefix) {
    if (!publicId.startsWith(prefix)) {
      return false;
    }
    for (String segment : publicId.substring(prefix.length()).split("/", -1)) {
      if (segment.isEmpty()
          || segment.equals(".")
          || segment.equals("..")
          || segment.contains("\\")
          || !segment.equals(segment.strip())
          || segment.codePoints().anyMatch(Character::isISOControl)) {
        return false;
      }
    }
    return true;
  }
}
