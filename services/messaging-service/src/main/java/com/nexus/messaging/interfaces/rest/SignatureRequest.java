package com.nexus.messaging.interfaces.rest;

import jakarta.validation.constraints.NotNull;

// Body xin chữ ký upload; channelId dạng chuỗi để sai định dạng thành 400 của miền
record SignatureRequest(@NotNull String channelId) {}
