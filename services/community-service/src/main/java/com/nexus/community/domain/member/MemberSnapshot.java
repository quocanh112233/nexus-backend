package com.nexus.community.domain.member;

import java.time.Instant;
import java.util.UUID;

// Bản chụp một dòng thành viên (server, user, thời điểm tham gia) không kèm role
public record MemberSnapshot(UUID serverId, UUID userId, Instant joinedAt) {}
