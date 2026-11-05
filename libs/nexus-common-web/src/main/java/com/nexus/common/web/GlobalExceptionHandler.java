package com.nexus.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Đổi exception thành Problem Details, ưu tiên thấp nhất để advice riêng của service thắng
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

  private static final String SECURITY_PACKAGE = "org.springframework.security.";

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  // Xử lý ApiException với status và code do nơi ném quyết định
  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, Object>> handleApi(ApiException ex, HttpServletRequest request) {
    return problem(ex.status(), ex.code(), ex.getMessage(), request, null);
  }

  // Xử lý lỗi validate body, liệt kê từng field sai
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> handleInvalidBody(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<Map<String, String>> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                e ->
                    Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
            .toList();
    return validationFailed(request, errors);
  }

  // Xử lý lỗi validate tham số, lấy tên cuối của property path làm field
  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<Map<String, Object>> handleConstraint(
      ConstraintViolationException ex, HttpServletRequest request) {
    List<Map<String, String>> errors =
        ex.getConstraintViolations().stream()
            .map(
                v -> {
                  String path = v.getPropertyPath().toString();
                  return Map.of(
                      "field",
                      path.substring(path.lastIndexOf('.') + 1),
                      "message",
                      v.getMessage());
                })
            .toList();
    return validationFailed(request, errors);
  }

  // Xử lý body không đọc được như JSON sai cú pháp
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> handleUnreadable(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Malformed request body", request, null);
  }

  // Xử lý tham số sai kiểu như UUID sai định dạng trong path variable
  @ExceptionHandler(TypeMismatchException.class)
  ResponseEntity<Map<String, Object>> handleTypeMismatch(
      TypeMismatchException ex, HttpServletRequest request) {
    List<Map<String, String>> errors =
        List.of(Map.of("field", String.valueOf(ex.getPropertyName()), "message", "invalid value"));
    return validationFailed(request, errors);
  }

  // Xử lý exception còn lại: bỏ qua Spring Security, giữ status của ErrorResponse, còn lại 500
  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> handleOthers(Exception ex, HttpServletRequest request)
      throws Exception {
    if (ex.getClass().getName().startsWith(SECURITY_PACKAGE)) {
      throw ex;
    }
    if (ex instanceof ErrorResponse errorResponse) {
      return handleSpringError(errorResponse, ex, request);
    }
    LOG.error("Unhandled exception on {}", request.getRequestURI(), ex);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "Unexpected server error",
        request,
        null);
  }

  // Đổi lỗi do Spring MVC tự ném thành Problem Details, giữ status gốc và không lộ message
  private ResponseEntity<Map<String, Object>> handleSpringError(
      ErrorResponse errorResponse, Exception ex, HttpServletRequest request) {
    HttpStatus status =
        Objects.requireNonNullElse(
            HttpStatus.resolve(errorResponse.getStatusCode().value()),
            HttpStatus.INTERNAL_SERVER_ERROR);
    if (status.is5xxServerError()) {
      LOG.error("Spring error on {}", request.getRequestURI(), ex);
    } else {
      LOG.debug("Client error on {}", request.getRequestURI(), ex);
    }
    String code =
        switch (status) {
          case NOT_FOUND -> "NOT_FOUND";
          case BAD_REQUEST -> "VALIDATION_FAILED";
          default -> status.name();
        };
    return problem(status, code, status.getReasonPhrase(), request, null);
  }

  // Dựng phản hồi 400 VALIDATION_FAILED kèm errors
  private ResponseEntity<Map<String, Object>> validationFailed(
      HttpServletRequest request, List<Map<String, String>> errors) {
    return problem(
        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, errors);
  }

  // Dựng ResponseEntity application/problem+json từ ProblemBodies
  private ResponseEntity<Map<String, Object>> problem(
      HttpStatus status,
      String code,
      String detail,
      HttpServletRequest request,
      List<Map<String, String>> errors) {
    Map<String, Object> body = ProblemBodies.body(status, code, detail, request.getRequestURI());
    if (errors != null) {
      body.put("errors", errors);
    }
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }
}
