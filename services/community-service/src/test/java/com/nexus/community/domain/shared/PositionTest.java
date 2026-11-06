package com.nexus.community.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.ServerId;
import org.junit.jupiter.api.Test;

// Kiểm thử Position và các định danh UUID
class PositionTest {

  // Kịch bản: vị trí âm bị từ chối, 0 hợp lệ
  // Input: -1 và 0
  // Output: -1 ném DomainValidationException, 0 tạo được Position
  @Test
  void rejectsNegative() {
    assertThatThrownBy(() -> new Position(-1)).isInstanceOf(DomainValidationException.class);
    assertThat(new Position(0).value()).isZero();
  }

  // Kịch bản: định danh phân tích được UUID hợp lệ, từ chối null và chuỗi sai
  // Input: UUID mới sinh dạng chuỗi, null và "not-a-uuid"
  // Output: of khớp newId, hai giá trị sai ném DomainValidationException
  @Test
  void idsParseOrReject() {
    UserId user = UserId.newId();
    assertThat(UserId.of(user.value().toString())).isEqualTo(user);
    ServerId server = ServerId.newId();
    assertThat(ServerId.of(server.value().toString())).isEqualTo(server);
    RoleId role = RoleId.newId();
    assertThat(RoleId.of(role.value().toString())).isEqualTo(role);
    CategoryId category = CategoryId.newId();
    assertThat(CategoryId.of(category.value().toString())).isEqualTo(category);
    ChannelId channel = ChannelId.newId();
    assertThat(ChannelId.of(channel.value().toString())).isEqualTo(channel);
    assertThatThrownBy(() -> UserId.of(null)).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> ChannelId.of("not-a-uuid"))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> new ServerId(null)).isInstanceOf(DomainValidationException.class);
  }
}
