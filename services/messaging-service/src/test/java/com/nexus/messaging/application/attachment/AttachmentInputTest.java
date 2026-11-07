package com.nexus.messaging.application.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

// Kiểm tra AttachmentInput.isUnder chặn thoát thư mục upload của user
class AttachmentInputTest {

  private static final String PREFIX = "nexus-dev/attachments/c1/abc/";

  // Kịch bản: publicId hợp lệ nằm dưới thư mục của user, gồm cả thư mục con
  // Input: pic, a/b/pic.png, tên có dấu cách giữa và dấu chấm
  // Output: isUnder trả true cho tất cả
  @Test
  void acceptsCleanPaths() {
    for (String rest : List.of("pic", "a/b/pic.png", "my file", "a.b", "...x", "a..b")) {
      assertThat(AttachmentInput.isUnder(PREFIX + rest, PREFIX)).as(rest).isTrue();
    }
  }

  // Kịch bản: publicId thoát hoặc lách thư mục của user
  // Input: user abcd, chữ hoa, thiếu gạch cuối, đoạn rỗng, dot, backslash, điều khiển, khoảng trắng
  // Output: isUnder trả false cho tất cả
  @Test
  void rejectsEscapesAndMalformedSegments() {
    List<String> bad =
        List.of(
            "nexus-dev/attachments/c1/abcd/pic",
            "nexus-dev/attachments/c1/ABC/pic",
            "nexus-dev/attachments/c1/abc",
            "nexus-dev/attachments/c2/abc/pic",
            PREFIX,
            PREFIX + "../../c2/b/x",
            PREFIX + "a/../x",
            PREFIX + "..",
            PREFIX + ".",
            PREFIX + "a/./b",
            PREFIX + "a//b",
            PREFIX + "a/",
            PREFIX + "a\\b",
            PREFIX + "a\u0001b",
            PREFIX + "a\nb",
            PREFIX + " x",
            PREFIX + "x ",
            PREFIX + "a/ b/c");
    for (String id : bad) {
      assertThat(AttachmentInput.isUnder(id, PREFIX)).as(id).isFalse();
    }
  }
}
