package com.nexus.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

// Lấy X-Request-Id hợp lệ hoặc sinh UUID, gắn vào request chuyển tiếp, response và log mỗi request
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

  static final String HEADER = "X-Request-Id";

  static final String ATTRIBUTE = "requestId";

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

  // Gắn request id rồi chạy tiếp chuỗi lọc, ghi đè header response để chỉ còn một giá trị
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    long start = System.nanoTime();
    ServerHttpRequest original = exchange.getRequest();
    String incoming = original.getHeaders().getFirst(HEADER);
    String id =
        incoming != null && SAFE.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
    ServerWebExchange mutated =
        exchange.mutate().request(r -> r.headers(h -> h.set(HEADER, id))).build();
    mutated.getAttributes().put(ATTRIBUTE, id);
    mutated
        .getResponse()
        .beforeCommit(
            () -> {
              mutated.getResponse().getHeaders().set(HEADER, id);
              return Mono.empty();
            });
    return chain
        .filter(mutated)
        .doFinally(
            signal ->
                LOG.info(
                    "requestId={} method={} path={} status={} ms={}",
                    id,
                    original.getMethod(),
                    PathMask.mask(original.getPath().value()),
                    status(mutated),
                    (System.nanoTime() - start) / 1_000_000));
  }

  // Mã status đã đặt cho response, chưa đặt thì mặc định 200
  private static int status(ServerWebExchange exchange) {
    HttpStatusCode code = exchange.getResponse().getStatusCode();
    return code == null ? 200 : code.value();
  }
}
