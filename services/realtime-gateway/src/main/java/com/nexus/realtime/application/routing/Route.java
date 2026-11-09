package com.nexus.realtime.application.routing;

import java.util.Map;
import java.util.UUID;

// Đích giao frame: userId null là topic; data null giữ nguyên payload, khác null thay payload
public record Route(String destination, UUID userId, Map<String, Object> data) {}
