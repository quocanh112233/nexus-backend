package com.nexus.messaging.interfaces.rest;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

// Body mở DM, bắt buộc có id người bạn
record OpenDmBody(@NotNull UUID friendId) {}
