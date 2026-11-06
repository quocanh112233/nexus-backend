package com.nexus.community.interfaces.rest;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// Body sửa category một phần, trường vắng là giữ nguyên
public record UpdateCategoryRequest(
    @Size(max = 100) String name, @PositiveOrZero Integer position) {}
