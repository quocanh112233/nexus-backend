package com.nexus.community.interfaces.rest;

import com.nexus.community.application.server.CategoryView;
import com.nexus.community.application.server.ChannelView;
import com.nexus.community.application.server.RoleView;
import com.nexus.community.application.server.ServerDetail;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Phản hồi chi tiết server vừa tạo kèm category, kênh và role
public record ServerDetailResponse(
    UUID id,
    String name,
    UUID ownerId,
    Instant createdAt,
    List<CategoryView> categories,
    List<ChannelView> channels,
    List<RoleView> roles) {

  // Dựng phản hồi phẳng từ ServerDetail
  static ServerDetailResponse from(ServerDetail d) {
    return new ServerDetailResponse(
        d.server().id(),
        d.server().name(),
        d.server().ownerId(),
        d.server().createdAt(),
        d.categories(),
        d.channels(),
        d.roles());
  }
}
