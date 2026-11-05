package com.nexus.common.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

// Dựng và ghi body Problem Details dùng chung cho exception handler và security entry point
public final class ProblemBodies {

  static final String REQUEST_ID_KEY = "requestId";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  // Chặn khởi tạo vì lớp chỉ chứa hàm static
  private ProblemBodies() {}

  // Dựng body Problem Details theo api-conventions mục 4, instance null thì bỏ qua
  public static Map<String, Object> body(
      HttpStatus status, String code, String detail, String instance) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "https://nexus.dev/errors/" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    body.put("title", title(code));
    body.put("status", status.value());
    body.put("code", code);
    body.put("detail", detail);
    if (instance != null) {
      body.put("instance", instance);
    }
    body.put(REQUEST_ID_KEY, MDC.get(REQUEST_ID_KEY));
    return body;
  }

  // Ghi body Problem Details ra response, dùng cho AuthenticationEntryPoint và AccessDeniedHandler
  public static void write(
      HttpServletResponse response, HttpStatus status, String code, String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.getWriter().write(MAPPER.writeValueAsString(body(status, code, detail, null)));
  }

  // Đổi code dạng PERMISSION_DENIED thành tiêu đề "Permission denied"
  private static String title(String code) {
    String words = code.toLowerCase(Locale.ROOT).replace('_', ' ');
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }
}
