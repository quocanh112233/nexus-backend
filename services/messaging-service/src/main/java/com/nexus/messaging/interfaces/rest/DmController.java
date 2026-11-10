package com.nexus.messaging.interfaces.rest;

import com.nexus.messaging.application.social.ListDmsQuery;
import com.nexus.messaging.application.social.OpenDmUseCase;
import com.nexus.messaging.application.social.OpenDmUseCase.OpenDmResult;
import com.nexus.messaging.domain.shared.UserId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Endpoint mở và liệt kê DM 1-1; danh sách không phân trang (ponytail: thêm cursor khi cần)
@RestController
@RequestMapping("/api/v1/dms")
class DmController {

  private final OpenDmUseCase openDm;
  private final ListDmsQuery listDms;

  // Nhận use case và query qua constructor
  DmController(OpenDmUseCase openDm, ListDmsQuery listDms) {
    this.openDm = openDm;
    this.listDms = listDms;
  }

  // Mở DM với bạn; 201 khi tạo mới, 200 khi đã có
  @PostMapping
  ResponseEntity<DmResponse> open(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OpenDmBody body) {
    OpenDmResult result = openDm.execute(UserId.of(jwt.getSubject()), new UserId(body.friendId()));
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(DmResponse.from(result.dm()));
  }

  // Mọi DM của user hiện tại, kể cả khi đã hết bạn
  @GetMapping
  DmResponse.Items list(@AuthenticationPrincipal Jwt jwt) {
    return new DmResponse.Items(
        listDms.execute(UserId.of(jwt.getSubject())).stream().map(DmResponse::from).toList());
  }
}
