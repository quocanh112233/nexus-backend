package com.nexus.community.application.ban;

import java.util.List;
import java.util.UUID;

// Một trang ban, lastUserId là user cuối trang khi còn trang sau và null khi hết
public record BanPage(List<BanView> items, UUID lastUserId) {}
