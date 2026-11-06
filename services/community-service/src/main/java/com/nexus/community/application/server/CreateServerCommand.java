package com.nexus.community.application.server;

import com.nexus.community.domain.shared.UserId;

// Lệnh tạo server: chủ sở hữu và tên thô chưa kiểm tra
public record CreateServerCommand(UserId owner, String name) {}
