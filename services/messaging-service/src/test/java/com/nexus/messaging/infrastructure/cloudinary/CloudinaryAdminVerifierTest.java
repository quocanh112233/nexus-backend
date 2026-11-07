package com.nexus.messaging.infrastructure.cloudinary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.port.VerifiedMedia;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Kiểm tra CloudinaryAdminVerifier với Cloudinary giả dựng bằng HttpServer của JDK
class CloudinaryAdminVerifierTest {

  private HttpServer server;
  private CloudinaryAdminVerifier verifier;
  private int status;
  private String body;
  private final AtomicReference<String> rawPath = new AtomicReference<>();
  private final AtomicReference<String> authHeader = new AtomicReference<>();

  // Dựng máy chủ giả cổng ngẫu nhiên trả status và body do từng test đặt
  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          rawPath.set(ex.getRequestURI().getRawPath());
          authHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().set("Content-Type", "application/json");
          ex.sendResponseHeaders(status, bytes.length);
          ex.getResponseBody().write(bytes);
          ex.close();
        });
    server.start();
    verifier =
        new CloudinaryAdminVerifier(
            new CloudinaryProperties(
                "demo",
                "key-1",
                "s3cr3t-value",
                "preset",
                "nexus-dev",
                "http://127.0.0.1:" + server.getAddress().getPort()));
  }

  // Tắt máy chủ giả
  @AfterEach
  void stop() {
    server.stop(0);
  }

  // Kịch bản: Cloudinary trả metadata hợp lệ của ảnh png
  // Input: 200 với secure_url, format png, bytes 1234, width 40, height 30
  // Output: VerifiedMedia(url, image/png, 1234, 40, 30)
  @Test
  void mapsMetadataFromAdminResponse() {
    ok(
        "{\"secure_url\":\"https://res/x.png\",\"format\":\"png\",\"bytes\":1234,"
            + "\"width\":40,\"height\":30}");

    VerifiedMedia media = verifier.verify("nexus-dev/attachments/c/u/x", "image");

    assertThat(media).isEqualTo(new VerifiedMedia("https://res/x.png", "image/png", 1234, 40, 30));
  }

  // Kịch bản: tệp raw không có width, height
  // Input: 200 với format pdf, không width và height
  // Output: contentType application/pdf, width và height null
  @Test
  void rawFileHasNoDimensions() {
    ok("{\"secure_url\":\"https://res/x.pdf\",\"format\":\"pdf\",\"bytes\":10}");

    VerifiedMedia media = verifier.verify("nexus-dev/attachments/c/u/x", "raw");

    assertThat(media.contentType()).isEqualTo("application/pdf");
    assertThat(media.width()).isNull();
    assertThat(media.height()).isNull();
  }

  // Kịch bản: tệp không tồn tại trên Cloudinary
  // Input: 404
  // Output: DomainValidationException
  @Test
  void notFoundBecomesValidationError() {
    status = 404;
    body = "{}";

    assertThatThrownBy(() -> verifier.verify("nexus-dev/attachments/c/u/x", "image"))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: Cloudinary lỗi máy chủ hoặc từ chối khóa
  // Input: 500 rồi 401
  // Output: cả hai ném DependencyUnavailableException
  @Test
  void serverErrorBecomesDependencyUnavailable() {
    status = 500;
    body = "{}";
    assertThatThrownBy(() -> verifier.verify("nexus-dev/attachments/c/u/x", "image"))
        .isInstanceOf(DependencyUnavailableException.class);

    status = 401;
    assertThatThrownBy(() -> verifier.verify("nexus-dev/attachments/c/u/x", "image"))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  // Kịch bản: Cloudinary không kết nối được
  // Input: máy chủ giả đã tắt
  // Output: DependencyUnavailableException
  @Test
  void connectionRefusedBecomesDependencyUnavailable() {
    server.stop(0);

    assertThatThrownBy(() -> verifier.verify("nexus-dev/attachments/c/u/x", "image"))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  // Kịch bản: Cloudinary trả phản hồi bất thường
  // Input: thiếu bytes, bytes là chuỗi, bytes âm, thiếu secure_url, secure_url rỗng
  // Output: mỗi ca ném DependencyUnavailableException, không NPE hay ClassCastException
  @Test
  void malformedResponseBecomesDependencyUnavailable() {
    for (String json :
        new String[] {
          "{\"secure_url\":\"https://res/x.png\",\"format\":\"png\"}",
          "{\"secure_url\":\"https://res/x.png\",\"format\":\"png\",\"bytes\":\"10\"}",
          "{\"secure_url\":\"https://res/x.png\",\"format\":\"png\",\"bytes\":-1}",
          "{\"format\":\"png\",\"bytes\":10}",
          "{\"secure_url\":\"\",\"format\":\"png\",\"bytes\":10}"
        }) {
      ok(json);

      assertThatThrownBy(() -> verifier.verify("nexus-dev/attachments/c/u/x", "image"))
          .as(json)
          .isInstanceOf(DependencyUnavailableException.class);
    }
  }

  // Kịch bản: tệp lớn hơn 25 MiB
  // Input: 200 với bytes 26214401
  // Output: DomainValidationException
  @Test
  void oversizeFileRejected() {
    ok("{\"secure_url\":\"https://res/x.png\",\"format\":\"png\",\"bytes\":26214401}");

    assertThatThrownBy(() -> verifier.verify("nexus-dev/attachments/c/u/x", "image"))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: định dạng ngoài danh sách cho phép
  // Input: 200 với format exe
  // Output: DomainValidationException
  @Test
  void disallowedFormatRejected() {
    ok("{\"secure_url\":\"https://res/x.exe\",\"format\":\"exe\",\"bytes\":10}");

    assertThatThrownBy(() -> verifier.verify("nexus-dev/attachments/c/u/x", "raw"))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: yêu cầu gửi Basic auth và publicId được mã hóa theo từng đoạn
  // Input: verify publicId có dấu cách ở đoạn cuối, resourceType raw
  // Output: path /demo/resources/raw/upload/.../my%20file và header Basic key-1:s3cr3t-value
  @Test
  void sendsBasicAuthAndEncodedPublicId() {
    ok("{\"secure_url\":\"https://res/x.txt\",\"format\":\"txt\",\"bytes\":10}");

    verifier.verify("nexus-dev/attachments/c/u/my file", "raw");

    assertThat(rawPath.get())
        .isEqualTo("/demo/resources/raw/upload/nexus-dev/attachments/c/u/my%20file");
    assertThat(authHeader.get())
        .isEqualTo(
            "Basic "
                + Base64.getEncoder()
                    .encodeToString("key-1:s3cr3t-value".getBytes(StandardCharsets.UTF_8)));
  }

  // Đặt phản hồi 200 với body JSON cho request kế tiếp
  private void ok(String json) {
    status = 200;
    body = json;
  }
}
