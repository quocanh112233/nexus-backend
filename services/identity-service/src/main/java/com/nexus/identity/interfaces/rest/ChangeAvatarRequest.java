package com.nexus.identity.interfaces.rest;

import jakarta.validation.constraints.NotBlank;

// Body đổi avatar mang publicId ảnh đã upload lên Cloudinary
public record ChangeAvatarRequest(@NotBlank String publicId) {}
