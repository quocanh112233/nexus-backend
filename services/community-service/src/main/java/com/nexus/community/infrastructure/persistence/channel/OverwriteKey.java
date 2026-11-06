package com.nexus.community.infrastructure.persistence.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

// Khóa chính ghép (channel_id, target_type, target_id) của bảng channel_permission_overwrites
@Embeddable
record OverwriteKey(
    @Column(name = "channel_id") UUID channelId,
    @Column(name = "target_type") String targetType,
    @Column(name = "target_id") UUID targetId)
    implements Serializable {}
