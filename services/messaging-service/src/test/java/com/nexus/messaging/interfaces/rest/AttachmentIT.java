package com.nexus.messaging.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.application.port.AttachmentVerifier;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.port.VerifiedMedia;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra chữ ký upload và gửi tin có đính kèm: tiền tố publicId, giới hạn, quyền, metadata thật
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "app.cloudinary.cloud-name=demo",
      "app.cloudinary.api-key=key-1",
      "app.cloudinary.api-secret=s3cr3t-value",
      "app.cloudinary.upload-preset=nexus_attachment",
      "app.cloudinary.folder-root=nexus-dev"
    })
class AttachmentIT extends MessagingIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;
  @MockitoBean private ChannelPermissionChecker checker;
  @MockitoBean private AttachmentVerifier verifier;

  private final UUID author = UUID.randomUUID();
  private final UUID channel = UUID.randomUUID();

  // Cho mọi user đủ quyền xem, gửi tin và đính kèm trước mỗi test
  @BeforeEach
  void grantAll() {
    allow(
        ChannelPermission.VIEW_CHANNEL,
        ChannelPermission.SEND_MESSAGES,
        ChannelPermission.ATTACH_FILES);
  }

  // Kịch bản: xin chữ ký upload ở kênh có ATTACH_FILES
  // Input: POST /attachments/signature {channelId}
  // Output: 200 với folder, preset, maxBytes, 10 định dạng, chữ ký hex, không lộ secret
  @Test
  void signatureEndpointReturnsFolderForChannelAndUser() throws Exception {
    HttpResponse<String> response =
        send("/api/v1/attachments/signature", Map.of("channelId", channel.toString()));

    JsonNode body = MAPPER.readTree(response.body());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(body.get("folder").asText())
        .isEqualTo("nexus-dev/attachments/" + channel + "/" + author);
    assertThat(body.get("cloudName").asText()).isEqualTo("demo");
    assertThat(body.get("apiKey").asText()).isEqualTo("key-1");
    assertThat(body.get("uploadPreset").asText()).isEqualTo("nexus_attachment");
    assertThat(body.get("maxBytes").asLong()).isEqualTo(26214400L);
    assertThat(body.get("allowedFormats")).hasSize(10);
    assertThat(body.get("signature").asText()).matches("[0-9a-f]{40}");
    assertThat(body.get("timestamp").asLong()).isPositive();
    assertThat(response.body()).doesNotContain("s3cr3t-value");
  }

  // Kịch bản: xin chữ ký khi thiếu ATTACH_FILES, không thấy kênh, hoặc channelId hỏng
  // Input: quyền chỉ VIEW_CHANNEL, rồi quyền rỗng, rồi channelId "abc"
  // Output: 403, 404, 400
  @Test
  void signatureEndpointChecksPermissionAndInput() throws Exception {
    allow(ChannelPermission.VIEW_CHANNEL);
    assertThat(send("/api/v1/attachments/signature", Map.of("channelId", channel.toString())))
        .extracting(HttpResponse::statusCode)
        .isEqualTo(403);

    allow();
    assertThat(send("/api/v1/attachments/signature", Map.of("channelId", channel.toString())))
        .extracting(HttpResponse::statusCode)
        .isEqualTo(404);

    assertThat(send("/api/v1/attachments/signature", Map.of("channelId", "abc")))
        .extracting(HttpResponse::statusCode)
        .isEqualTo(400);
  }

  // Kịch bản: gửi tin kèm tệp trong thư mục của chính mình
  // Input: content "look" và một attachment ảnh, verifier trả bytes 1234, 40x30
  // Output: 201, hàng message_attachments và payload outbox mang metadata từ verifier
  @Test
  void sendWithOwnPublicIdStoresAttachmentWithVerifiedMetadata() throws Exception {
    String publicId = ownPrefix(channel, author) + "pic";
    when(verifier.verify(publicId, "image"))
        .thenReturn(new VerifiedMedia("https://res/pic.png", "image/png", 1234, 40, 30));

    HttpResponse<String> response =
        post(
            channel,
            Map.of(
                "content",
                "look",
                "attachments",
                List.of(
                    Map.of(
                        "publicId",
                        publicId,
                        "resourceType",
                        "image",
                        "originalFilename",
                        "pic.png"))));

    JsonNode body = MAPPER.readTree(response.body());
    UUID id = UUID.fromString(body.get("id").asText());
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(body.get("attachments").get(0).get("bytes").asLong()).isEqualTo(1234);
    Map<String, Object> row =
        jdbc.queryForMap(
            "select public_id, url, content_type, bytes, original_filename"
                + " from messaging.message_attachments where message_id = ?",
            id);
    assertThat(row)
        .containsEntry("public_id", publicId)
        .containsEntry("url", "https://res/pic.png")
        .containsEntry("content_type", "image/png")
        .containsEntry("bytes", 1234L)
        .containsEntry("original_filename", "pic.png");
    assertThat(MAPPER.readTree(outbox(id)).get("attachments").get(0).get("bytes").asLong())
        .isEqualTo(1234);
  }

  // Kịch bản: tin chỉ có tệp, content rỗng, không gửi originalFilename
  // Input: content "" và một attachment raw pdf
  // Output: 201, content "", tên tệp lấy đoạn cuối publicId, payload outbox có bytes
  @Test
  void attachmentOnlyMessageWithEmptyContentIs201() throws Exception {
    String publicId = ownPrefix(channel, author) + "doc.pdf";
    when(verifier.verify(publicId, "raw"))
        .thenReturn(new VerifiedMedia("https://res/doc.pdf", "application/pdf", 99, null, null));

    HttpResponse<String> response =
        post(
            channel,
            Map.of(
                "content",
                "",
                "attachments",
                List.of(Map.of("publicId", publicId, "resourceType", "raw"))));

    JsonNode body = MAPPER.readTree(response.body());
    UUID id = UUID.fromString(body.get("id").asText());
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(body.get("content").asText()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select original_filename from messaging.message_attachments where message_id = ?",
                String.class,
                id))
        .isEqualTo("doc.pdf");
    assertThat(MAPPER.readTree(outbox(id)).get("attachments").get(0).get("bytes").asLong())
        .isEqualTo(99);
  }

  // Kịch bản: publicId nằm trong thư mục của user khác
  // Input: attachment có publicId theo tiền tố của user khác
  // Output: 400 VALIDATION_FAILED, verifier không bị gọi, không lưu tin
  @Test
  void publicIdOfAnotherUserIs400AndVerifierNeverCalled() throws Exception {
    String publicId = ownPrefix(channel, UUID.randomUUID()) + "pic";

    assertBadAttachment(publicId, "image");
    verifyNoInteractions(verifier);
  }

  // Kịch bản: publicId nằm trong thư mục của kênh khác
  // Input: attachment có publicId theo tiền tố của kênh khác, cùng user
  // Output: 400 VALIDATION_FAILED, verifier không bị gọi, không lưu tin
  @Test
  void publicIdOfAnotherChannelIs400() throws Exception {
    String publicId = ownPrefix(UUID.randomUUID(), author) + "pic";

    assertBadAttachment(publicId, "image");
    verifyNoInteractions(verifier);
  }

  // Kịch bản: publicId có đoạn không hợp lệ dưới đúng tiền tố
  // Input: "..", "//", ".", "\\", ký tự điều khiển, khoảng trắng đầu hoặc cuối
  // Output: mỗi ca 400 VALIDATION_FAILED, không lưu tin, verifier không bị gọi
  @Test
  void unsafePublicIdSegmentsAre400AndVerifierNeverCalled() throws Exception {
    String own = ownPrefix(channel, author);
    String other = "../../" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/x";
    for (String rest : List.of(other, "a//b", ".", "a/./b", "a\\b", "a\u0001b", " x", "x ")) {
      assertBadAttachment(own + rest, "image");
    }
    verifyNoInteractions(verifier);
  }

  // Kịch bản: danh sách attachments chứa phần tử null
  // Input: content hợp lệ và attachments là [null]
  // Output: 400 VALIDATION_FAILED, verifier không bị gọi, không lưu tin
  @Test
  void nullAttachmentElementIs400() throws Exception {
    HttpResponse<String> response =
        post(channel, Map.of("content", "x", "attachments", Arrays.asList((Object) null)));

    assertCode(response, 400, "VALIDATION_FAILED");
    assertThat(countMessages()).isZero();
    verifyNoInteractions(verifier);
  }

  // Kịch bản: originalFilename dài 256 ký tự
  // Input: attachment hợp lệ nhưng originalFilename 256 chữ a
  // Output: 400 VALIDATION_FAILED, verifier không bị gọi, không lưu tin
  @Test
  void originalFilenameOver255Is400() throws Exception {
    HttpResponse<String> response =
        post(
            channel,
            Map.of(
                "content",
                "x",
                "attachments",
                List.of(
                    Map.of(
                        "publicId",
                        ownPrefix(channel, author) + "pic",
                        "resourceType",
                        "image",
                        "originalFilename",
                        "a".repeat(256)))));

    assertCode(response, 400, "VALIDATION_FAILED");
    assertThat(countMessages()).isZero();
    verifyNoInteractions(verifier);
  }

  // Kịch bản: originalFilename chứa ký tự NUL
  // Input: attachment hợp lệ nhưng originalFilename "a\u0000b.png"
  // Output: 400 VALIDATION_FAILED, verifier không bị gọi, không lưu tin
  @Test
  void originalFilenameWithNulIs400() throws Exception {
    HttpResponse<String> response =
        post(
            channel,
            Map.of(
                "content",
                "x",
                "attachments",
                List.of(
                    Map.of(
                        "publicId",
                        ownPrefix(channel, author) + "pic",
                        "resourceType",
                        "image",
                        "originalFilename",
                        "a\u0000b.png"))));

    assertCode(response, 400, "VALIDATION_FAILED");
    assertThat(countMessages()).isZero();
    verifyNoInteractions(verifier);
  }

  // Kịch bản: client gửi lại cùng nonce kèm attachment
  // Input: hai POST cùng nonce và attachment, verifier chỉ stub cho lần đầu
  // Output: 201 rồi 200 cùng id, lần gửi lại không gọi verifier
  @Test
  void replayWithAttachmentDoesNotCallVerifierAgain() throws Exception {
    String publicId = ownPrefix(channel, author) + "pic";
    when(verifier.verify(publicId, "image"))
        .thenReturn(new VerifiedMedia("https://res/pic.png", "image/png", 10, 4, 3));
    Map<String, Object> body =
        Map.of(
            "content",
            "x",
            "nonce",
            "n-att",
            "attachments",
            List.of(Map.of("publicId", publicId, "resourceType", "image")));

    HttpResponse<String> first = post(channel, body);
    clearInvocations(verifier);
    HttpResponse<String> second = post(channel, body);

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(second.body()).get("id").asText())
        .isEqualTo(MAPPER.readTree(first.body()).get("id").asText());
    verifyNoInteractions(verifier);
  }

  // Kịch bản: Cloudinary không dùng được khi xác minh tệp
  // Input: verifier ném DependencyUnavailableException
  // Output: 503 DEPENDENCY_UNAVAILABLE, không còn hàng ở messages, attachments, refs, outbox
  @Test
  void verifierDownIs503AndLeavesNoRows() throws Exception {
    String publicId = ownPrefix(channel, author) + "pic";
    when(verifier.verify(publicId, "image"))
        .thenThrow(new DependencyUnavailableException("down", new RuntimeException()));

    HttpResponse<String> response =
        post(
            channel,
            Map.of(
                "content",
                "x",
                "attachments",
                List.of(Map.of("publicId", publicId, "resourceType", "image"))));

    assertCode(response, 503, "DEPENDENCY_UNAVAILABLE");
    assertThat(countMessages()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from messaging.message_attachments where public_id = ?",
                Integer.class,
                publicId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from messaging.channel_refs where channel_id = ?",
                Integer.class,
                channel))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from messaging.outbox_event where payload->>'channelId' = ?",
                Integer.class,
                channel.toString()))
        .isZero();
  }

  // Kịch bản: không attachment và content rỗng hoặc thiếu
  // Input: content "" rồi body không có content
  // Output: cả hai 400 VALIDATION_FAILED, không lưu tin
  @Test
  void emptyContentWithoutAttachmentIs400() throws Exception {
    assertCode(post(channel, Map.of("content", "")), 400, "VALIDATION_FAILED");
    assertCode(post(channel, Map.of("nonce", "n-1")), 400, "VALIDATION_FAILED");
    assertThat(countMessages()).isZero();
  }

  // Kịch bản: resourceType ngoài image, raw, video
  // Input: attachment resourceType "audio"
  // Output: 400 VALIDATION_FAILED, verifier không bị gọi
  @Test
  void unknownResourceTypeIs400() throws Exception {
    assertBadAttachment(ownPrefix(channel, author) + "pic", "audio");
    verifyNoInteractions(verifier);
  }

  // Kịch bản: sáu attachment trong một tin
  // Input: 6 attachment đều đúng tiền tố
  // Output: 400 VALIDATION_FAILED, verifier không bị gọi, không lưu tin
  @Test
  void sixAttachmentsIs400() throws Exception {
    List<Map<String, String>> six = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      six.add(Map.of("publicId", ownPrefix(channel, author) + "f" + i, "resourceType", "image"));
    }

    HttpResponse<String> response = post(channel, Map.of("content", "x", "attachments", six));

    assertCode(response, 400, "VALIDATION_FAILED");
    assertThat(countMessages()).isZero();
    verifyNoInteractions(verifier);
  }

  // Kịch bản: có SEND_MESSAGES nhưng thiếu ATTACH_FILES
  // Input: quyền VIEW_CHANNEL, SEND_MESSAGES và một attachment hợp lệ
  // Output: 403 PERMISSION_DENIED, không lưu tin, verifier không bị gọi
  @Test
  void attachmentWithoutAttachFilesIs403() throws Exception {
    allow(ChannelPermission.VIEW_CHANNEL, ChannelPermission.SEND_MESSAGES);

    HttpResponse<String> response =
        post(
            channel,
            Map.of(
                "content",
                "x",
                "attachments",
                List.of(
                    Map.of(
                        "publicId", ownPrefix(channel, author) + "pic", "resourceType", "image"))));

    assertCode(response, 403, "PERMISSION_DENIED");
    assertThat(countMessages()).isZero();
    verifyNoInteractions(verifier);
  }

  // Gửi một attachment có publicId và resourceType cho trước rồi khẳng định 400 và không lưu tin
  private void assertBadAttachment(String publicId, String resourceType) throws Exception {
    HttpResponse<String> response =
        post(
            channel,
            Map.of(
                "content",
                "x",
                "attachments",
                List.of(Map.of("publicId", publicId, "resourceType", resourceType))));

    assertCode(response, 400, "VALIDATION_FAILED");
    assertThat(countMessages()).isZero();
  }

  // Tiền tố thư mục upload của một user trong một kênh
  private static String ownPrefix(UUID channelId, UUID userId) {
    return "nexus-dev/attachments/" + channelId + "/" + userId + "/";
  }

  // Cấp đúng các quyền cho mọi user trên mọi kênh
  private void allow(ChannelPermission... permissions) {
    EnumSet<ChannelPermission> set = EnumSet.noneOf(ChannelPermission.class);
    set.addAll(List.of(permissions));
    when(checker.check(any(), any())).thenReturn(new ChannelAccess(UUID.randomUUID(), set));
  }

  // Khẳng định status và code của Problem Details
  private void assertCode(HttpResponse<String> response, int status, String code) throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(MAPPER.readTree(response.body()).get("code").asText()).isEqualTo(code);
  }

  // Đếm tin của tác giả mặc định
  private int countMessages() {
    return jdbc.queryForObject(
        "select count(*) from messaging.messages where author_id = ?", Integer.class, author);
  }

  // Payload event message.created của một tin
  private String outbox(UUID messageId) {
    return jdbc.queryForObject(
        "select payload::text from messaging.outbox_event"
            + " where routing_key = 'message.created' and payload->>'messageId' = ?",
        String.class,
        messageId.toString());
  }

  // POST tin vào kênh với Bearer của tác giả mặc định
  private HttpResponse<String> post(UUID channelId, Map<String, Object> body) throws Exception {
    return send("/api/v1/channels/" + channelId + "/messages", body);
  }

  // POST body JSON tới đường dẫn với Bearer của tác giả mặc định
  private HttpResponse<String> send(String path, Map<String, Object> body) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer(author))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
