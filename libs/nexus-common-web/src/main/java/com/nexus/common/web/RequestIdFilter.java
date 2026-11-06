package com.nexus.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

// Lấy X-Request-Id hợp lệ hoặc sinh UUID, đặt vào MDC và header response, ghi log mỗi request
public class RequestIdFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Request-Id";

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

  // Lấy hoặc sinh X-Request-Id, đưa vào MDC và header phản hồi, ghi một dòng log khi xử lý xong
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
    long start = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      LOG.info(
          "method={} path={} status={} ms={}",
          request.getMethod(),
          pattern != null ? pattern : PathMask.mask(request.getRequestURI()),
          response.getStatus(),
          (System.nanoTime() - start) / 1_000_000);
      MDC.remove(ProblemBodies.REQUEST_ID_KEY);
    }
  }
}
