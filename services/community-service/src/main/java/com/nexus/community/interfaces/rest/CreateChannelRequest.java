package com.nexus.community.interfaces.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// Body tạo kênh, categoryId vắng hoặc null là không category, position vắng là xếp cuối nhóm
public record CreateChannelRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 1024) String topic,
    String categoryId,
    @PositiveOrZero Integer position) {}
