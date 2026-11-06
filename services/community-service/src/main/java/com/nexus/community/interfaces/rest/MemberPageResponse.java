package com.nexus.community.interfaces.rest;

import com.nexus.community.application.member.MemberPage;
import java.util.List;

// Phản hồi một trang thành viên kèm con trỏ trang sau
public record MemberPageResponse(List<MemberResponse> items, String nextCursor) {

  // Dựng phản hồi từ trang, con trỏ là base64url không padding của userId cuối trang
  static MemberPageResponse from(MemberPage page) {
    return new MemberPageResponse(
        page.items().stream().map(MemberResponse::from).toList(),
        Cursors.encode(page.lastUserId()));
  }
}
