package com.nexus.community.interfaces.rest;

import com.nexus.common.web.ProblemBodies;
import com.nexus.community.application.channel.CategoryNotFoundException;
import com.nexus.community.application.channel.ChannelNotFoundException;
import com.nexus.community.application.invite.InviteInvalidException;
import com.nexus.community.application.invite.InviteNotFoundException;
import com.nexus.community.application.server.PermissionDeniedException;
import com.nexus.community.application.server.ServerNotFoundException;
import com.nexus.community.domain.shared.DomainValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Đổi lỗi miền của community thành Problem Details, ưu tiên cao hơn handler chung của thư viện
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class CommunityExceptionHandler {

  // Vi phạm bất biến miền thành 400 VALIDATION_FAILED
  @ExceptionHandler(DomainValidationException.class)
  ResponseEntity<Map<String, Object>> handleValidation(
      DomainValidationException ex, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), request);
  }

  // Server, category, kênh hoặc lời mời không có (hay không phải thành viên) thành 404 NOT_FOUND
  @ExceptionHandler({
    ServerNotFoundException.class,
    CategoryNotFoundException.class,
    ChannelNotFoundException.class,
    InviteNotFoundException.class
  })
  ResponseEntity<Map<String, Object>> handleNotFound(
      RuntimeException ex, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
  }

  // Lời mời không dùng được để tham gia thành 410 INVITE_INVALID
  @ExceptionHandler(InviteInvalidException.class)
  ResponseEntity<Map<String, Object>> handleInviteInvalid(
      InviteInvalidException ex, HttpServletRequest request) {
    return problem(HttpStatus.GONE, "INVITE_INVALID", ex.getMessage(), request);
  }

  // Thành viên thiếu quyền thành 403 PERMISSION_DENIED
  @ExceptionHandler(PermissionDeniedException.class)
  ResponseEntity<Map<String, Object>> handleForbidden(
      PermissionDeniedException ex, HttpServletRequest request) {
    return problem(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", ex.getMessage(), request);
  }

  // Ghi đè trên phiên bản cũ thành 409 VERSION_CONFLICT
  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<Map<String, Object>> handleVersionConflict(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT, "VERSION_CONFLICT", "Resource was modified concurrently", request);
  }

  // Dựng ResponseEntity application/problem+json từ ProblemBodies
  private static ResponseEntity<Map<String, Object>> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ProblemBodies.body(status, code, detail, request.getRequestURI()));
  }
}
