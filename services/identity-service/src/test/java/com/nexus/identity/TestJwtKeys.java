package com.nexus.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.util.Base64;

// Sinh cặp khóa RSA 2048 lúc chạy test và ghi khóa riêng ra file PEM PKCS#8 tạm
public final class TestJwtKeys {

  // Chặn khởi tạo vì lớp chỉ chứa hàm static
  private TestJwtKeys() {}

  // Sinh một cặp khóa RSA 2048 bit mới
  public static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // Ghi khóa riêng dạng PEM PKCS#8 vào file tạm tự xóa khi JVM dừng, trả đường dẫn file
  public static Path writePem(PrivateKey privateKey) {
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(privateKey.getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    try {
      Path file = Files.createTempFile("nexus-jwt-", ".pem");
      file.toFile().deleteOnExit();
      return Files.writeString(file, pem);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
