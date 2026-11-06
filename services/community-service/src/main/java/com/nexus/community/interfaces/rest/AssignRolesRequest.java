package com.nexus.community.interfaces.rest;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

// Body gán role cho thành viên, roleIds bắt buộc và rỗng nghĩa là gỡ hết
public record AssignRolesRequest(@NotNull List<@NotNull UUID> roleIds) {}
