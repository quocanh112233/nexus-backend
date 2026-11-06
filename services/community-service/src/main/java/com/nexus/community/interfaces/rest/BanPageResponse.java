package com.nexus.community.interfaces.rest;

import com.nexus.community.application.ban.BanPage;
import java.util.List;

// Phản hồi một trang ban kèm con trỏ trang sau
public record BanPageResponse(List<BanResponse> items, String nextCursor) {

  // Dựng phản hồi từ trang, con trỏ là base64url không padding của userId cuối trang
  static BanPageResponse from(BanPage page) {
    return new BanPageResponse(
        page.items().stream().map(BanResponse::from).toList(), Cursors.encode(page.lastUserId()));
  }
}
