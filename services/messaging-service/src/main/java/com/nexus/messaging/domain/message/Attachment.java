package com.nexus.messaging.domain.message;

import java.util.UUID;

// Tệp đính kèm của tin nhắn sau khi đã xác thực với kho lưu trữ
public record Attachment(
    UUID id,
    String publicId,
    String url,
    String contentType,
    long bytes,
    String originalFilename) {}
