package com.nexus.community.interfaces.rest;

import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.channel.ChannelChanges;
import com.nexus.community.domain.channel.ChannelName;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.Position;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

// Body sửa kênh một phần, categoryId là JsonNode: Java null là vắng, NullNode là null tường minh
public record UpdateChannelRequest(
    @Size(max = 100) String name,
    @Size(max = 1024) String topic,
    @PositiveOrZero Integer position,
    JsonNode categoryId) {

  // Đổi sang ChannelChanges, categoryId không phải chuỗi UUID thì ném lỗi miền
  ChannelChanges toChanges() {
    boolean change = categoryId != null;
    CategoryId category = null;
    if (change && !categoryId.isNull()) {
      if (!categoryId.isString()) {
        throw new DomainValidationException("categoryId is not a valid UUID");
      }
      category = CategoryId.of(categoryId.asString());
    }
    return new ChannelChanges(
        name == null ? null : new ChannelName(name),
        topic,
        position == null ? null : new Position(position),
        change,
        category);
  }
}
