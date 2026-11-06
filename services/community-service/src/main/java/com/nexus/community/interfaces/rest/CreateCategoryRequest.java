package com.nexus.community.interfaces.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// Body tạo category, position vắng nghĩa là xếp cuối
public record CreateCategoryRequest(
    @NotBlank @Size(max = 100) String name, @PositiveOrZero Integer position) {}
