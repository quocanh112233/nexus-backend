package com.nexus.community.application.server;

import java.util.List;

// Chi tiết server vừa tạo: tóm tắt cùng category, kênh và role
public record ServerDetail(
    ServerSummary server,
    List<CategoryView> categories,
    List<ChannelView> channels,
    List<RoleView> roles) {}
