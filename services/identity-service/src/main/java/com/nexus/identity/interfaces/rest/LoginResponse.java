package com.nexus.identity.interfaces.rest;

// Phản hồi đăng nhập: access token Bearer và thời gian sống tính bằng giây
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {

  // Che token để không lộ qua log
  @Override
  public String toString() {
    return "LoginResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
  }
}
