package com.nexus.messaging.interfaces.rest;

import com.nexus.messaging.application.social.CancelFriendRequestUseCase;
import com.nexus.messaging.application.social.FriendRequestView;
import com.nexus.messaging.application.social.ListFriendRequestsQuery;
import com.nexus.messaging.application.social.ListFriendsQuery;
import com.nexus.messaging.application.social.RemoveFriendUseCase;
import com.nexus.messaging.application.social.RespondFriendRequestUseCase;
import com.nexus.messaging.application.social.SendFriendRequestUseCase;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendRequestId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Endpoint bạn bè và lời mời kết bạn; danh sách không phân trang (ponytail: thêm cursor khi cần)
@RestController
@RequestMapping("/api/v1/friends")
class FriendController {

  private final SendFriendRequestUseCase sendRequest;
  private final RespondFriendRequestUseCase respond;
  private final CancelFriendRequestUseCase cancel;
  private final RemoveFriendUseCase removeFriend;
  private final ListFriendsQuery listFriends;
  private final ListFriendRequestsQuery listRequests;

  // Nhận các use case và query qua constructor
  FriendController(
      SendFriendRequestUseCase sendRequest,
      RespondFriendRequestUseCase respond,
      CancelFriendRequestUseCase cancel,
      RemoveFriendUseCase removeFriend,
      ListFriendsQuery listFriends,
      ListFriendRequestsQuery listRequests) {
    this.sendRequest = sendRequest;
    this.respond = respond;
    this.cancel = cancel;
    this.removeFriend = removeFriend;
    this.listFriends = listFriends;
    this.listRequests = listRequests;
  }

  // Danh sách id bạn bè của user hiện tại
  @GetMapping
  FriendListResponse friends(@AuthenticationPrincipal Jwt jwt) {
    return FriendListResponse.of(listFriends.execute(UserId.of(jwt.getSubject())));
  }

  // Xóa một người bạn, không phải bạn thì 404
  @DeleteMapping("/{userId}")
  ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
    removeFriend.execute(UserId.of(jwt.getSubject()), new UserId(userId));
    return ResponseEntity.noContent().build();
  }

  // Lời mời đang chờ theo hướng incoming (mặc định) hoặc outgoing, hướng khác thì 400
  @GetMapping("/requests")
  FriendRequestResponse.Items requests(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "incoming") String direction) {
    UserId me = UserId.of(jwt.getSubject());
    List<FriendRequestView> views =
        switch (direction) {
          case "incoming" -> listRequests.incoming(me);
          case "outgoing" -> listRequests.outgoing(me);
          default -> throw new DomainValidationException("direction must be incoming or outgoing");
        };
    return new FriendRequestResponse.Items(
        views.stream().map(FriendRequestResponse::from).toList());
  }

  // Gửi lời mời; 201 khi tạo PENDING mới, 200 khi mời ngược chiều và đã tự chấp nhận
  @PostMapping("/requests")
  ResponseEntity<FriendRequestResponse> send(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SendFriendRequestBody body) {
    FriendRequestView view =
        sendRequest.execute(UserId.of(jwt.getSubject()), new UserId(body.targetUserId()));
    HttpStatus status = "ACCEPTED".equals(view.status()) ? HttpStatus.OK : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(FriendRequestResponse.from(view));
  }

  // Người nhận chấp nhận lời mời
  @PostMapping("/requests/{requestId}/accept")
  FriendRequestResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId) {
    return FriendRequestResponse.from(
        respond.accept(UserId.of(jwt.getSubject()), new FriendRequestId(requestId)));
  }

  // Người nhận từ chối lời mời
  @PostMapping("/requests/{requestId}/decline")
  FriendRequestResponse decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId) {
    return FriendRequestResponse.from(
        respond.decline(UserId.of(jwt.getSubject()), new FriendRequestId(requestId)));
  }

  // Người gửi hủy lời mời đang chờ
  @DeleteMapping("/requests/{requestId}")
  ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId) {
    cancel.execute(UserId.of(jwt.getSubject()), new FriendRequestId(requestId));
    return ResponseEntity.noContent().build();
  }
}
