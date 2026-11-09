package com.nexus.presence.application.presence;

// Cổng phát PresenceChanged ra broker, lỗi gửi không được ném ra ngoài
public interface PresenceChangePublisher {

  // Phát một thay đổi hiện diện
  void publish(PresenceChange change);
}
