package com.nexus.identity.infrastructure.cloudinary;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.application.port.UploadSignature;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// Kiểm tra CloudinaryUploadSigner không cần Spring
class CloudinaryUploadSignerTest {

  private static final CloudinaryProperties PROPS =
      new CloudinaryProperties("demo", "key-1", "s3cr3t-value", "nexus_avatar", "nexus-dev");
  private final CloudinaryUploadSigner signer = new CloudinaryUploadSigner(PROPS);

  // Kịch bản: thuật toán khớp ví dụ chính thức trong tài liệu chữ ký xác thực của Cloudinary
  // Input: tham số eager, public_id, timestamp=1315060510 của ví dụ, secret abcd
  // Output: bfd09f95f331f558cbd1320e67aa8d488770583e
  @Test
  void signatureMatchesCloudinaryDocExample() {
    String params =
        "eager=w_400,h_300,c_pad|w_260,h_200,c_crop&public_id=sample_image&timestamp=1315060510";

    assertThat(CloudinaryUploadSigner.signature(params, "abcd"))
        .isEqualTo("bfd09f95f331f558cbd1320e67aa8d488770583e");
  }

  // Kịch bản: đổi folder thì chữ ký đổi
  // Input: sign hai folder khác nhau cùng thời điểm
  // Output: hai chữ ký khác nhau, mỗi chữ ký 40 ký tự hex và timestamp là giây epoch
  @Test
  void signatureChangesWithFolder() {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");

    UploadSignature a = signer.sign("nexus-dev/avatars/a", now);
    UploadSignature b = signer.sign("nexus-dev/avatars/b", now);

    assertThat(a.signature()).isNotEqualTo(b.signature()).matches("[0-9a-f]{40}");
    assertThat(a.timestamp()).isEqualTo(now.getEpochSecond());
    assertThat(a.folder()).isEqualTo("nexus-dev/avatars/a");
  }

  // Kịch bản: secret không lọt ra chữ ký trả về hay chuỗi cấu hình
  // Input: sign một folder rồi toString chữ ký và cấu hình
  // Output: không chuỗi nào chứa API secret và publicUrl dựng đúng URL ảnh
  @Test
  void neverContainsSecretInResponse() {
    UploadSignature sig = signer.sign("nexus-dev/avatars/a", Instant.parse("2026-02-01T00:00:00Z"));

    assertThat(sig.toString()).doesNotContain("s3cr3t-value");
    assertThat(PROPS.toString()).doesNotContain("s3cr3t-value");
    assertThat(signer.publicUrl("nexus-dev/avatars/a/x"))
        .isEqualTo("https://res.cloudinary.com/demo/image/upload/nexus-dev/avatars/a/x");
  }
}
