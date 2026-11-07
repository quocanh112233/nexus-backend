package com.nexus.messaging.interfaces.rest;

import com.nexus.messaging.application.attachment.CreateAttachmentSignatureUseCase;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Endpoint cấp chữ ký để client upload tệp đính kèm thẳng lên Cloudinary
@RestController
@RequestMapping("/api/v1/attachments")
class AttachmentController {

  private final CreateAttachmentSignatureUseCase createSignature;

  // Nhận use case qua constructor
  AttachmentController(CreateAttachmentSignatureUseCase createSignature) {
    this.createSignature = createSignature;
  }

  // Cấp chữ ký upload vào thư mục của user trong kênh
  @PostMapping("/signature")
  SignatureResponse signature(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SignatureRequest request) {
    return SignatureResponse.from(
        createSignature.execute(ChannelId.of(request.channelId()), UserId.of(jwt.getSubject())));
  }
}
