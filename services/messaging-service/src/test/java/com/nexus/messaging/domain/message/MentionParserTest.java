package com.nexus.messaging.domain.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra MentionParser tách mention <@uuid> hợp lệ, gộp trùng và giới hạn 20
class MentionParserTest {

  // Kịch bản: hai người khác nhau được nhắc
  // Input: "hi <@u1> and <@u2>"
  // Output: tập gồm đúng u1 rồi u2 theo thứ tự xuất hiện
  @Test
  void extractsTwoDistinctMentions() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();

    var result = MentionParser.parse("hi <@" + u1 + "> and <@" + u2 + ">");

    assertThat(result).containsExactly(new UserId(u1), new UserId(u2));
  }

  // Kịch bản: một người bị nhắc nhiều lần
  // Input: "<@u1> <@u1>"
  // Output: tập chỉ có u1 một lần
  @Test
  void dedupesRepeatedMention() {
    UUID u1 = UUID.randomUUID();

    assertThat(MentionParser.parse("<@" + u1 + "> <@" + u1 + ">")).containsExactly(new UserId(u1));
  }

  // Kịch bản: token sai định dạng bị bỏ qua
  // Input: "<@abc> <@123> <@------------------------------------>"
  // Output: tập rỗng
  @Test
  void ignoresMalformedIds() {
    assertThat(MentionParser.parse("<@abc> <@123> <@------------------------------------>"))
        .isEmpty();
  }

  // Kịch bản: nhắc quá 20 người khác nhau
  // Input: 21 mention UUID khác nhau
  // Output: DomainValidationException
  @Test
  void rejects21DistinctMentions() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 21; i++) {
      text.append("<@").append(UUID.randomUUID()).append("> ");
    }

    assertThatThrownBy(() -> MentionParser.parse(text.toString()))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: đúng 20 người khác nhau vẫn hợp lệ
  // Input: 20 mention UUID khác nhau
  // Output: tập có 20 phần tử
  @Test
  void accepts20DistinctMentions() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      text.append("<@").append(UUID.randomUUID()).append("> ");
    }

    assertThat(MentionParser.parse(text.toString())).hasSize(20);
  }
}
