package com.nexus.community.domain.channel;

import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.shared.Position;

// Thay đổi một phần của kênh, trường null là giữ nguyên, changeCategory đặt categoryId kể cả null
public record ChannelChanges(
    ChannelName name,
    String topic,
    Position position,
    boolean changeCategory,
    CategoryId categoryId) {}
