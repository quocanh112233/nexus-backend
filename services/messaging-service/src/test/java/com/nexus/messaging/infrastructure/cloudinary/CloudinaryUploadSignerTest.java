package com.nexus.messaging.infrastructure.cloudinary;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.messaging.application.port.UploadSignature;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// Kiểm tra CloudinaryUploadSigner không cần Spring
class CloudinaryUploadSignerTest {

  private static final CloudinaryProperties PROPS =
      new CloudinaryProperties(
          "demo", "key-1", "s3cr3t-value", "nexus_attachment", "nexus-dev", "http://x");
  private final CloudinaryUploadSigner signer = new CloudinaryUploadSigner(PROPS);

  // Kịch bản: thuật toán khớp ví dụ chính thức trong tài liệu chữ ký xác thực của Cloudinary
  // Input: tham số eager, public_id, timestamp=1315060510 của ví dụ, secret abcd
  // Output: bfd09f95f331f558cbd1320e67aa8d488770583e
  @Test
  void signatureMatchesKnownVector() {
    String params =
        "eager=w_400,h_300,c_pad|w_260,h_200,c_crop&public_id=sample_image&timestamp=1315060510";

    assertThat(CloudinaryUploadSigner.signature(params, "abcd"))
        .isEqualTo("bfd09f95f331f558cbd1320e67aa8d488770583e");
  }

  // Kịch bản: ký folder, timestamp, upload_preset đúng thứ tự tên tham số
  // Input: sign folder nexus-dev/attachments/c1/u1 lúc 2026-02-01T00:00:00Z
  // Output: chữ ký bằng SHA-1 sinh ngoài (sha1sum) của chuỗi tham số cộng secret
  @Test
  void signUsesSortedParams() {
    UploadSignature sig =
        signer.sign("nexus-dev/attachments/c1/u1", Instant.parse("2026-02-01T00:00:00Z"));

    assertThat(sig.signature()).isEqualTo("f18cc2232fc15a351017539b3483dd6a0217675f");
    assertThat(sig.timestamp()).isEqualTo(1769904000L);
    assertThat(sig.cloudName()).isEqualTo("demo");
    assertThat(sig.uploadPreset()).isEqualTo("nexus_attachment");
    assertThat(signer.folderRoot()).isEqualTo("nexus-dev");
  }

  // Kịch bản: secret không lọt ra chữ ký trả về hay chuỗi cấu hình
  // Input: sign một folder rồi toString chữ ký và cấu hình
  // Output: không chuỗi nào chứa API secret
  @Test
  void neverContainsSecretInResponse() {
    UploadSignature sig =
        signer.sign("nexus-dev/attachments/c1/u1", Instant.parse("2026-02-01T00:00:00Z"));

    assertThat(sig.toString()).doesNotContain("s3cr3t-value");
    assertThat(PROPS.toString()).doesNotContain("s3cr3t-value");
  }
}
