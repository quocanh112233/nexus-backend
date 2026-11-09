package com.nexus.presence.interfaces.rest;

import com.nexus.common.web.ApiException;
import com.nexus.presence.application.presence.QueryPresenceUseCase;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Endpoint tra trạng thái hiện diện của tối đa 200 user, chỉ trả người viewer được thấy
@RestController
@RequestMapping("/api/v1/presence")
class PresenceController {

  private static final int MAX_IDS = 200;

  private final QueryPresenceUseCase query;

  // Nhận use case qua constructor
  PresenceController(QueryPresenceUseCase query) {
    this.query = query;
  }

  // Gộp id trùng, id sai UUID hoặc quá 200 thì 400 VALIDATION_FAILED; viewer là sub của JWT
  @GetMapping
  List<PresenceResponse> get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "userIds", required = false) List<String> userIds) {
    Set<UUID> targets = new LinkedHashSet<>();
    try {
      for (String raw : userIds == null ? List.<String>of() : userIds) {
        for (String id : raw.split(",")) {
          if (!id.isBlank()) {
            targets.add(UUID.fromString(id.trim()));
          }
        }
      }
    } catch (IllegalArgumentException e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "userIds must be UUIDs");
    }
    if (targets.size() > MAX_IDS) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "userIds must have at most 200 entries");
    }
    return query
        .execute(UUID.fromString(jwt.getSubject()), List.copyOf(targets))
        .entrySet()
        .stream()
        .map(e -> new PresenceResponse(e.getKey(), e.getValue()))
        .toList();
  }
}
