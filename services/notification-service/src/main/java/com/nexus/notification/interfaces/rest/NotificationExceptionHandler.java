package com.nexus.notification.interfaces.rest;

import com.nexus.common.web.ProblemBodies;
import com.nexus.notification.application.shared.ChannelNotFoundException;
import com.nexus.notification.domain.shared.DomainValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Đổi lỗi miền của notification thành Problem Details, ưu tiên cao hơn handler chung của thư viện
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class NotificationExceptionHandler {

  // Vi phạm bất biến miền thành 400 VALIDATION_FAILED
  @ExceptionHandler(DomainValidationException.class)
  ResponseEntity<Map<String, Object>> handleValidation(
      DomainValidationException ex, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), request);
  }

  // Kênh không tồn tại hay user không thấy được thành 404 NOT_FOUND
  @ExceptionHandler(ChannelNotFoundException.class)
  ResponseEntity<Map<String, Object>> handleNotFound(
      ChannelNotFoundException ex, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
  }

  // Dựng ResponseEntity application/problem+json từ ProblemBodies
  private static ResponseEntity<Map<String, Object>> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ProblemBodies.body(status, code, detail, request.getRequestURI()));
  }
}
