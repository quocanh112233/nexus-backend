package com.nexus.messaging.infrastructure.cloudinary;

import com.nexus.messaging.application.port.AttachmentVerifier;
import com.nexus.messaging.application.port.VerifiedMedia;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import com.nexus.messaging.domain.shared.DomainValidationException;
import java.net.URI;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

// Xác minh tệp qua Cloudinary Admin API bằng Basic auth, timeout kết nối 2 s và đọc 3 s
@Component
class CloudinaryAdminVerifier implements AttachmentVerifier {

  private static final Map<String, String> CONTENT_TYPES =
      Map.of(
          "jpg", "image/jpeg",
          "jpeg", "image/jpeg",
          "png", "image/png",
          "gif", "image/gif",
          "webp", "image/webp",
          "pdf", "application/pdf",
          "txt", "text/plain",
          "zip", "application/zip",
          "mp4", "video/mp4",
          "mp3", "audio/mpeg");

  private final CloudinaryProperties props;
  private final RestClient client;

  // Dựng RestClient có timeout và Basic auth từ cấu hình
  CloudinaryAdminVerifier(CloudinaryProperties props) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(2000);
    factory.setReadTimeout(3000);
    this.props = props;
    this.client =
        RestClient.builder()
            .requestFactory(factory)
            .defaultHeaders(h -> h.setBasicAuth(props.apiKey(), props.apiSecret()))
            .build();
  }

  // Gọi Admin API: 404 là lỗi miền, lỗi khác của Cloudinary là phụ thuộc không dùng được
  @Override
  public VerifiedMedia verify(String publicId, String resourceType) {
    Map<String, Object> body;
    try {
      body =
          client
              .get()
              .uri(uri(publicId, resourceType))
              .retrieve()
              .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    } catch (HttpClientErrorException.NotFound e) {
      throw new DomainValidationException("attachment not found: " + publicId);
    } catch (RestClientException e) {
      throw new DependencyUnavailableException("cloudinary unavailable", e);
    }
    if (body == null) {
      throw new DependencyUnavailableException("cloudinary returned empty body", null);
    }
    if (!(body.get("bytes") instanceof Number number) || number.longValue() < 0) {
      throw new DependencyUnavailableException("cloudinary returned invalid bytes", null);
    }
    if (!(body.get("secure_url") instanceof String url) || url.isBlank()) {
      throw new DependencyUnavailableException("cloudinary returned no secure_url", null);
    }
    long bytes = number.longValue();
    String format = format(body, publicId);
    if (bytes > MAX_BYTES) {
      throw new DomainValidationException("attachment exceeds " + MAX_BYTES + " bytes");
    }
    if (!ALLOWED_FORMATS.contains(format)) {
      throw new DomainValidationException("attachment format not allowed: " + format);
    }
    return new VerifiedMedia(
        url, CONTENT_TYPES.get(format), bytes, asInt(body.get("width")), asInt(body.get("height")));
  }

  // URL Admin API, publicId mã hóa từng đoạn và giữ dấu gạch chéo
  private URI uri(String publicId, String resourceType) {
    return UriComponentsBuilder.fromUriString(props.apiBaseUrl())
        .pathSegment(props.cloudName(), "resources", resourceType, "upload")
        .pathSegment(publicId.split("/"))
        .build()
        .encode()
        .toUri();
  }

  // Định dạng tệp; tệp raw không có format thì lấy đuôi của publicId
  private static String format(Map<String, Object> body, String publicId) {
    String value =
        body.get("format") instanceof String format
            ? format
            : publicId.substring(publicId.lastIndexOf('.') + 1);
    return value.toLowerCase();
  }

  // Số nguyên từ JSON, null khi vắng
  private static Integer asInt(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }
}
