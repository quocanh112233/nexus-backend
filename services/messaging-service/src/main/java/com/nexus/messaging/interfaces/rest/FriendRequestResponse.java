package com.nexus.messaging.interfaces.rest;

import com.nexus.messaging.application.social.FriendRequestView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Phản hồi một lời mời kết bạn; status là PENDING, ACCEPTED, DECLINED hoặc CANCELED
record FriendRequestResponse(
    UUID id, UUID requesterId, UUID addresseeId, String status, Instant createdAt) {

  // Danh sách lời mời, không phân trang
  record Items(List<FriendRequestResponse> items) {}

  // Dựng phản hồi từ góc nhìn lời mời
  static FriendRequestResponse from(FriendRequestView v) {
    return new FriendRequestResponse(
        v.id(), v.requesterId(), v.addresseeId(), v.status(), v.createdAt());
  }
}
