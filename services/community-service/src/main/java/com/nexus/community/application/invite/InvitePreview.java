package com.nexus.community.application.invite;

import java.time.Instant;

// Xem trước lời mời công khai: tên server, số thành viên và hạn
public record InvitePreview(String code, String serverName, long memberCount, Instant expiresAt) {}
