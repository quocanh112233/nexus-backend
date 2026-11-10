package com.nexus.messaging.interfaces.rest;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

// Body gửi lời mời kết bạn, bắt buộc có user đích
record SendFriendRequestBody(@NotNull UUID targetUserId) {}
