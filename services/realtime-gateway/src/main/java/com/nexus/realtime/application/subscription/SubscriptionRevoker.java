package com.nexus.realtime.application.subscription;

// Cổng gỡ subscription khỏi broker và báo cho user, sổ subscription đã được xóa từ trước
public interface SubscriptionRevoker {

  // Gỡ subscription khỏi broker rồi gửi user frame SubscriptionRevoked kèm lý do (eventType)
  void revoke(SessionSubscription subscription, String reason);
}
