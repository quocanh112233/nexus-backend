package com.nexus.identity.interfaces.rest;

import com.nexus.identity.application.port.UploadSignature;

// Chữ ký upload trả cho client, không bao giờ chứa API secret
public record AvatarSignatureResponse(
    String cloudName,
    String apiKey,
    long timestamp,
    String signature,
    String folder,
    String uploadPreset) {

  // Dựng phản hồi từ UploadSignature
  static AvatarSignatureResponse from(UploadSignature s) {
    return new AvatarSignatureResponse(
        s.cloudName(), s.apiKey(), s.timestamp(), s.signature(), s.folder(), s.uploadPreset());
  }
}
