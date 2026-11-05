package com.nexus.identity.domain.user;

import com.nexus.identity.domain.shared.DomainValidationException;

// Tham chiếu tới ảnh đã lưu gồm publicId và url
public record MediaRef(String publicId, String url) {

  // Từ chối publicId hoặc url rỗng
  public MediaRef {
    if (publicId == null || publicId.isBlank() || url == null || url.isBlank()) {
      throw new DomainValidationException("media publicId and url are required");
    }
  }
}
