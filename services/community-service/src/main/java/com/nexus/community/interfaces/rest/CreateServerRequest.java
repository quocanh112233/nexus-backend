package com.nexus.community.interfaces.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Body tạo server, kiểm tra tên ở biên REST
public record CreateServerRequest(@NotBlank @Size(max = 100) String name) {}
