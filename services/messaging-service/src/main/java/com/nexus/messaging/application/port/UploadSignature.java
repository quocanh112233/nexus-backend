package com.nexus.messaging.application.port;

// Chữ ký upload ký sẵn để client tải tệp thẳng lên Cloudinary
public record UploadSignature(
    String cloudName,
    String apiKey,
    long timestamp,
    String signature,
    String folder,
    String uploadPreset) {}
