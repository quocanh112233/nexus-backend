package com.nexus.notification.application.port;

// Cổng ghi nhận các lần backfill đã hoàn tất, cùng transaction với dữ liệu nạp
public interface BackfillState {

  // True nếu backfill tên name đã hoàn tất
  boolean isDone(String name);

  // Đánh dấu backfill tên name đã hoàn tất, bỏ qua nếu đã có
  void markDone(String name);
}
