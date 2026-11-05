package com.nexus.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

// Lấy X-Request-Id hợp lệ hoặc sinh UUID, đặt vào MDC và header response
public class RequestIdFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Request-Id";

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String id =
        incoming != null && SAFE.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
    MDC.put(ProblemBodies.REQUEST_ID_KEY, id);
    response.setHeader(HEADER, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(ProblemBodies.REQUEST_ID_KEY);
    }
  }
}
