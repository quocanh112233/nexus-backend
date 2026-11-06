package com.nexus.community.application.member;

import java.util.List;
import java.util.UUID;

// Một trang thành viên, lastUserId là user cuối trang khi còn trang sau và null khi hết
public record MemberPage(List<MemberView> items, UUID lastUserId) {}
