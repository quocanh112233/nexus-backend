package com.nexus.messaging.application.port;

// Metadata tệp do kho lưu trữ xác nhận; width và height null với tệp không phải ảnh hay video
public record VerifiedMedia(
    String url, String contentType, long bytes, Integer width, Integer height) {}
