package com.nexus.messaging.interfaces.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

// Body gửi tin; content tối đa 8000 char thô, rỗng được khi có attachments, trường khác null được
record SendMessageRequest(
    @Size(max = 8000) String content,
    String nonce,
    UUID replyToId,
    List<@NotNull @Valid AttachmentRequest> attachments) {

  // Tệp đã upload lên Cloudinary; metadata do server lấy lại từ Cloudinary
  record AttachmentRequest(
      @NotBlank String publicId,
      @NotBlank @Pattern(regexp = "image|raw|video") String resourceType,
      @Size(max = 255) String originalFilename) {}
}
