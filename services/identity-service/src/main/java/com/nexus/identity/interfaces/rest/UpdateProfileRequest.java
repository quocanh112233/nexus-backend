package com.nexus.identity.interfaces.rest;

import jakarta.validation.constraints.Size;

// Body cập nhật hồ sơ, field null nghĩa là giữ nguyên
public record UpdateProfileRequest(
    @Size(max = 32) String displayName, @Size(max = 190) String aboutMe) {}
