package com.nexus.identity.interfaces.rest;

import com.nexus.common.web.ProblemBodies;
import com.nexus.identity.application.auth.InvalidCredentialsException;
import com.nexus.identity.application.auth.InvalidRefreshTokenException;
import com.nexus.identity.application.auth.RefreshTokenReusedException;
import com.nexus.identity.application.user.InvalidMediaException;
import com.nexus.identity.application.user.UserNotFoundException;
import com.nexus.identity.domain.shared.DomainValidationException;
import com.nexus.identity.domain.user.EmailAlreadyTakenException;
import com.nexus.identity.domain.user.UsernameAlreadyTakenException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Đổi lỗi miền của identity thành Problem Details, ưu tiên cao hơn handler chung của thư viện
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class IdentityExceptionHandler {

  private final RefreshCookies cookies;

  // Nhận bộ dựng cookie để xóa refresh token khi refresh thất bại
  IdentityExceptionHandler(RefreshCookies cookies) {
    this.cookies = cookies;
  }

  // Vi phạm bất biến miền thành 400 VALIDATION_FAILED
  @ExceptionHandler(DomainValidationException.class)
  ResponseEntity<Map<String, Object>> handleValidation(
      DomainValidationException ex, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), request);
  }

  // publicId avatar không hợp lệ thành 400 VALIDATION_FAILED
  @ExceptionHandler(InvalidMediaException.class)
  ResponseEntity<Map<String, Object>> handleInvalidMedia(
      InvalidMediaException ex, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), request);
  }

  // Email trùng thành 409 EMAIL_TAKEN
  @ExceptionHandler(EmailAlreadyTakenException.class)
  ResponseEntity<Map<String, Object>> handleEmailTaken(HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Email is already registered", request);
  }

  // Username trùng thành 409 USERNAME_TAKEN
  @ExceptionHandler(UsernameAlreadyTakenException.class)
  ResponseEntity<Map<String, Object>> handleUsernameTaken(HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "USERNAME_TAKEN", "Username is already taken", request);
  }

  // Đăng nhập sai thành 401 UNAUTHENTICATED với thông điệp chung
  @ExceptionHandler(InvalidCredentialsException.class)
  ResponseEntity<Map<String, Object>> handleInvalidCredentials(HttpServletRequest request) {
    return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Invalid credentials", request);
  }

  // Refresh token thiếu, lạ hoặc hết hạn thành 401 UNAUTHENTICATED chung kèm xóa cookie
  @ExceptionHandler(InvalidRefreshTokenException.class)
  ResponseEntity<Map<String, Object>> handleInvalidRefresh(HttpServletRequest request) {
    return clearCookie(
        problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Invalid refresh token", request));
  }

  // Refresh token bị dùng lại thành 401 REFRESH_TOKEN_REUSED kèm xóa cookie
  @ExceptionHandler(RefreshTokenReusedException.class)
  ResponseEntity<Map<String, Object>> handleRefreshReused(HttpServletRequest request) {
    return clearCookie(
        problem(
            HttpStatus.UNAUTHORIZED,
            "REFRESH_TOKEN_REUSED",
            "Refresh token reuse detected",
            request));
  }

  // Không có user thành 404 NOT_FOUND
  @ExceptionHandler(UserNotFoundException.class)
  ResponseEntity<Map<String, Object>> handleUserNotFound(HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found", request);
  }

  // Ghi đè trên phiên bản cũ thành 409 VERSION_CONFLICT
  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<Map<String, Object>> handleVersionConflict(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT, "VERSION_CONFLICT", "Resource was modified concurrently", request);
  }

  // Gắn Set-Cookie xóa nexus_rt vào response lỗi
  private ResponseEntity<Map<String, Object>> clearCookie(
      ResponseEntity<Map<String, Object>> response) {
    return ResponseEntity.status(response.getStatusCode())
        .headers(response.getHeaders())
        .header(HttpHeaders.SET_COOKIE, cookies.clear())
        .body(response.getBody());
  }

  // Dựng ResponseEntity application/problem+json từ ProblemBodies
  private static ResponseEntity<Map<String, Object>> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ProblemBodies.body(status, code, detail, request.getRequestURI()));
  }
}
