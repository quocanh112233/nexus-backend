package com.nexus.messaging.interfaces.rest;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Body sửa tin; chặn thô 8000 char, độ dài đúng do miền kiểm theo code point
record EditMessageRequest(@NotNull @Size(max = 8000) String content) {}
