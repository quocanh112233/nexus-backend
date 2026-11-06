package com.nexus.community.infrastructure.persistence.member;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

// Khóa chính ghép (server_id, user_id) của bảng members
@Embeddable
record MemberKey(@Column(name = "server_id") UUID serverId, @Column(name = "user_id") UUID userId)
    implements Serializable {}
