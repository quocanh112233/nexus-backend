package com.nexus.gateway;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;
import tools.jackson.databind.json.JsonMapper;

// Đổi lỗi định tuyến thành Problem Details: 503 service sau chết, 429 hết hạn mức, 404 không route
@Component
@Order(-2)
public class GatewayErrorHandler implements WebExceptionHandler {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Logger LOG = LoggerFactory.getLogger(GatewayErrorHandler.class);

  // Phân loại exception rồi ghi Problem Details, response đã gửi thì trả lại lỗi gốc
  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(ex);
    }
    String path = exchange.getRequest().getPath().value();
    if (isDependencyDown(ex)) {
      LOG.warn("Downstream unavailable on {}: {}", PathMask.mask(path), ex.toString());
      return write(
          exchange,
          HttpStatus.SERVICE_UNAVAILABLE,
          "DEPENDENCY_UNAVAILABLE",
          "Downstream service unavailable",
          path);
    }
    if (ex instanceof HttpClientErrorException.TooManyRequests) {
      return write(
          exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests", path);
    }
    if (ex instanceof ResponseStatusException rse) {
      HttpStatus status =
          Objects.requireNonNullElse(
              HttpStatus.resolve(rse.getStatusCode().value()), HttpStatus.INTERNAL_SERVER_ERROR);
      String code =
          switch (status) {
            case NOT_FOUND -> "NOT_FOUND";
            case BAD_REQUEST -> "VALIDATION_FAILED";
            default -> status.name();
          };
      return write(exchange, status, code, status.getReasonPhrase(), path);
    }
    LOG.error("Unhandled exception on {}", PathMask.mask(path), ex);
    return write(
        exchange,
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "Unexpected server error",
        path);
  }

  // Lỗi kết nối, DNS, đóng sớm, timeout hay 5xx tới service phía sau hoặc JWKS ở mọi tầng cause
  private static boolean isDependencyDown(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof ConnectException
          || t instanceof UnknownHostException
          || t instanceof TimeoutException
          || t instanceof PrematureCloseException
          || t instanceof WebClientRequestException
          || t instanceof WebClientResponseException wre && wre.getStatusCode().is5xxServerError()
          || t instanceof ResponseStatusException rse
              && rse.getStatusCode().value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
        return true;
      }
    }
    return false;
  }

  // Ghi body Problem Details giống ProblemBodies của libs, kèm requestId của RequestIdFilter
  static Mono<Void> write(
      ServerWebExchange exchange, HttpStatus status, String code, String detail, String instance) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "https://nexus.dev/errors/" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    body.put("title", title(code));
    body.put("status", status.value());
    body.put("code", code);
    body.put("detail", detail);
    if (instance != null) {
      body.put("instance", instance);
    }
    body.put("requestId", exchange.getAttribute(RequestIdFilter.ATTRIBUTE));
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    return response.writeWith(
        Mono.fromSupplier(() -> response.bufferFactory().wrap(MAPPER.writeValueAsBytes(body))));
  }

  // Đổi code dạng PERMISSION_DENIED thành tiêu đề "Permission denied"
  private static String title(String code) {
    String words = code.toLowerCase(Locale.ROOT).replace('_', ' ');
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }
}
