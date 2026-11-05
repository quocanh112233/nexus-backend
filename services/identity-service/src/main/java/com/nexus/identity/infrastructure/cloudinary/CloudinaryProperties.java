package com.nexus.identity.infrastructure.cloudinary;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Cấu hình Cloudinary đọc từ app.cloudinary, toString che apiSecret
@ConfigurationProperties("app.cloudinary")
public record CloudinaryProperties(
    String cloudName, String apiKey, String apiSecret, String uploadPreset, String folderRoot) {

  // Che apiSecret để không lọt vào log
  @Override
  public String toString() {
    return "CloudinaryProperties[cloudName="
        + cloudName
        + ", apiKey="
        + apiKey
        + ", apiSecret=***, uploadPreset="
        + uploadPreset
        + ", folderRoot="
        + folderRoot
        + "]";
  }
}
