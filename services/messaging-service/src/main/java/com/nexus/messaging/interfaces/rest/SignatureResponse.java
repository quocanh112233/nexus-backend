package com.nexus.messaging.interfaces.rest;

import com.nexus.messaging.application.port.AttachmentVerifier;
import com.nexus.messaging.application.port.UploadSignature;
import java.util.List;

// Phản hồi chữ ký upload kèm giới hạn định dạng và dung lượng
record SignatureResponse(
    String cloudName,
    String apiKey,
    long timestamp,
    String signature,
    String folder,
    String uploadPreset,
    List<String> allowedFormats,
    long maxBytes) {

  // Dựng phản hồi từ chữ ký
  static SignatureResponse from(UploadSignature s) {
    return new SignatureResponse(
        s.cloudName(),
        s.apiKey(),
        s.timestamp(),
        s.signature(),
        s.folder(),
        s.uploadPreset(),
        AttachmentVerifier.ALLOWED_FORMATS,
        AttachmentVerifier.MAX_BYTES);
  }
}
