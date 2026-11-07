package com.nexus.messaging.infrastructure.cloudinary;

import com.nexus.messaging.application.port.UploadSignature;
import com.nexus.messaging.application.port.UploadSigner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

// Ký yêu cầu upload Cloudinary bằng SHA-1 của tham số đã sắp xếp cộng API secret, không dùng SDK
@Component
@EnableConfigurationProperties(CloudinaryProperties.class)
class CloudinaryUploadSigner implements UploadSigner {

  private final CloudinaryProperties props;

  // Nhận cấu hình Cloudinary qua constructor
  CloudinaryUploadSigner(CloudinaryProperties props) {
    this.props = props;
  }

  // Ký folder, timestamp, upload_preset theo thứ tự tên tham số
  @Override
  public UploadSignature sign(String folder, Instant now) {
    long timestamp = now.getEpochSecond();
    String params =
        "folder=" + folder + "&timestamp=" + timestamp + "&upload_preset=" + props.uploadPreset();
    return new UploadSignature(
        props.cloudName(),
        props.apiKey(),
        timestamp,
        signature(params, props.apiSecret()),
        folder,
        props.uploadPreset());
  }

  // Trả thư mục gốc cấu hình cho môi trường này
  @Override
  public String folderRoot() {
    return props.folderRoot();
  }

  // SHA-1 hex của chuỗi tham số nối với secret
  static String signature(String params, String secret) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-1")
              .digest((params + secret).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
