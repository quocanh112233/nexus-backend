package com.nexus.messaging.application.port;

import java.util.List;

// Cổng xác minh tệp đã upload với kho lưu trữ, không tin metadata của client
public interface AttachmentVerifier {

  long MAX_BYTES = 26_214_400L;
  List<String> ALLOWED_FORMATS =
      List.of("jpg", "jpeg", "png", "gif", "webp", "pdf", "txt", "zip", "mp4", "mp3");

  // Lấy metadata thật của tệp; không có thì ném lỗi miền, kho lỗi thì ném DependencyUnavailable
  VerifiedMedia verify(String publicId, String resourceType);
}
