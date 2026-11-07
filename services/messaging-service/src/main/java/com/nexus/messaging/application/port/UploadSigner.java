package com.nexus.messaging.application.port;

import java.time.Instant;

// Cổng ký yêu cầu upload tệp đính kèm
public interface UploadSigner {

  // Ký yêu cầu upload vào folder tại thời điểm now
  UploadSignature sign(String folder, Instant now);

  // Thư mục gốc của mọi tệp thuộc môi trường này
  String folderRoot();
}
