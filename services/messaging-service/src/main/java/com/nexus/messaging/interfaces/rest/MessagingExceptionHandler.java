package com.nexus.messaging.interfaces.rest;

import com.nexus.common.web.ProblemBodies;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import com.nexus.messaging.application.shared.MessageNotFoundException;
import com.nexus.messaging.application.shared.PermissionDeniedException;
import com.nexus.messaging.domain.shared.DomainValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Đổi lỗi miền của messaging thành Problem Details, ưu tiên cao hơn handler chung của thư viện
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class MessagingExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(MessagingExceptionHandler.class);

  // Vi phạm bất biến miền thành 400 VALIDATION_FAILED
  @ExceptionHandler(DomainValidationException.class)
  ResponseEntity<Map<String, Object>> handleValidation(
      DomainValidationException ex, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), request);
  }

  // Kênh hoặc tin không tồn tại, hoặc user không thấy kênh, thành 404
  @ExceptionHandler({ChannelNotFoundException.class, MessageNotFoundException.class})
  ResponseEntity<Map<String, Object>> handleNotFound(
      RuntimeException ex, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
  }

  // User thiếu quyền thành 403 PERMISSION_DENIED
  @ExceptionHandler(PermissionDeniedException.class)
  ResponseEntity<Map<String, Object>> handleForbidden(
      PermissionDeniedException ex, HttpServletRequest request) {
    return problem(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", ex.getMessage(), request);
  }

  // Community không trả lời thì fail-closed thành 503, không lộ nguyên nhân gốc
  @ExceptionHandler(DependencyUnavailableException.class)
  ResponseEntity<Map<String, Object>> handleDependencyDown(
      DependencyUnavailableException ex, HttpServletRequest request) {
    LOG.warn("Dependency unavailable, returning 503: {}", ex.toString());
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "DEPENDENCY_UNAVAILABLE",
        "Dependency temporarily unavailable",
        request);
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
