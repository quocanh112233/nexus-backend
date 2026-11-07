package com.nexus.messaging.domain.reaction;

// Tổng hợp một emoji trên một tin: số lượt thả và người xem đã thả hay chưa
public record ReactionCount(String emoji, int count, boolean me) {}
