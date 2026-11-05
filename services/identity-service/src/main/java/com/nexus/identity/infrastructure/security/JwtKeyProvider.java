package com.nexus.identity.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Nạp khóa riêng RSA PKCS#8 từ file PEM, dẫn xuất public key và dựng RSAKey Nimbus kèm kid
@Component
class JwtKeyProvider {

  private final RSAKey rsaKey;

  // Đọc khóa lúc khởi động, thiếu hoặc sai định dạng thì dừng ngay với thông báo rõ ràng
  JwtKeyProvider(
      @Value("${app.jwt.private-key-path:}") String privateKeyPath,
      @Value("${app.jwt.key-id:nexus-1}") String keyId) {
    if (privateKeyPath == null || privateKeyPath.isBlank()) {
      throw new IllegalStateException(
          "app.jwt.private-key-path (JWT_PRIVATE_KEY_PATH) is not set: "
              + "it must point to a PKCS#8 PEM RSA private key");
    }
    RSAPrivateCrtKey privateKey = readPrivateKey(Path.of(privateKeyPath));
    try {
      RSAPublicKey publicKey =
          (RSAPublicKey)
              KeyFactory.getInstance("RSA")
                  .generatePublic(
                      new RSAPublicKeySpec(
                          privateKey.getModulus(), privateKey.getPublicExponent()));
      this.rsaKey =
          new RSAKey.Builder(publicKey)
              .privateKey(privateKey)
              .keyID(keyId)
              .algorithm(JWSAlgorithm.RS256)
              .keyUse(KeyUse.SIGNATURE)
              .build();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot derive RSA public key from JWT private key", e);
    }
  }

  // Khóa RSA đầy đủ cả phần riêng, chỉ dùng để ký
  RSAKey rsaKey() {
    return rsaKey;
  }

  // Public key RSA dùng để verify token
  RSAPublicKey publicKey() {
    try {
      return rsaKey.toRSAPublicKey();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  // Đọc file PEM PKCS#8 và trả khóa riêng RSA dạng CRT, không đưa nội dung khóa vào thông báo lỗi
  private static RSAPrivateCrtKey readPrivateKey(Path path) {
    String pem;
    try {
      pem = Files.readString(path);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Cannot read JWT private key file app.jwt.private-key-path=" + path, e);
    }
    if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
      throw new IllegalStateException(
          "JWT private key file " + path + " is not a PKCS#8 PEM (BEGIN PRIVATE KEY)");
    }
    try {
      String base64 =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      var key =
          KeyFactory.getInstance("RSA")
              .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
      if (key instanceof RSAPrivateCrtKey crtKey) {
        return crtKey;
      }
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "JWT private key file " + path + " is not a valid PKCS#8 RSA private key");
    }
    throw new IllegalStateException(
        "JWT private key file " + path + " does not contain RSA CRT parameters");
  }
}
