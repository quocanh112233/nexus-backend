package com.nexus.identity.application.port;

import java.time.Instant;

// Cổng ký yêu cầu upload và dựng url công khai của ảnh đã lưu
public interface UploadSigner {

  // Ký yêu cầu upload vào folder tại thời điểm now
  UploadSignature sign(String folder, Instant now);

  // Dựng url công khai từ publicId
  String publicUrl(String publicId);

  // Thư mục gốc của mọi ảnh thuộc môi trường này
  String folderRoot();
}
