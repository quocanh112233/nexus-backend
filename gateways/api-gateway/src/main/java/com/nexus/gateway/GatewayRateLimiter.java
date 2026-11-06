package com.nexus.gateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

// RedisRateLimiter có công tắc tắt, thêm Retry-After khi từ chối và ghi WARN thưa khi Redis lỗi
public class GatewayRateLimiter extends RedisRateLimiter {

  private static final Logger LOG = LoggerFactory.getLogger(RateLimitConfig.class);

  private static final long WARN_EVERY_NANOS = 60_000_000_000L;

  private final boolean enabled;
  private final AtomicLong lastWarn = new AtomicLong(System.nanoTime() - WARN_EVERY_NANOS);

  // Nhận template Redis, script Lua, dịch vụ cấu hình của gateway và công tắc bật/tắt
  public GatewayRateLimiter(
      ReactiveStringRedisTemplate redisTemplate,
      RedisScript<List<Long>> script,
      ConfigurationService configurationService,
      boolean enabled) {
    super(redisTemplate, script, configurationService);
    this.enabled = enabled;
  }

  // Tắt thì cho qua không chạm Redis; bật thì hỏi Redis rồi gắn Retry-After cho kết quả từ chối
  @Override
  public Mono<Response> isAllowed(String routeId, String id) {
    if (!enabled) {
      return Mono.just(new Response(true, Map.of()));
    }
    return super.isAllowed(routeId, id).map(this::decorate);
  }

  // Remaining -1 là Redis lỗi đã cho qua, bị từ chối thì gắn Retry-After đủ token cho 1 request
  private Response decorate(Response response) {
    Map<String, String> headers = response.getHeaders();
    long remaining = number(headers, getRemainingHeader());
    if (remaining < 0) {
      warnRedisDown();
      return response;
    }
    if (response.isAllowed()) {
      return response;
    }
    long rate = Math.max(1, number(headers, getReplenishRateHeader()));
    long missing = number(headers, getRequestedTokensHeader()) - remaining;
    Map<String, String> denied = new LinkedHashMap<>(headers);
    denied.put("Retry-After", String.valueOf(Math.max(1, Math.ceilDiv(missing, rate))));
    return new Response(false, denied);
  }

  // Đọc header số của limiter, thiếu thì 0
  private static long number(Map<String, String> headers, String name) {
    String value = headers.get(name);
    return value == null ? 0 : Long.parseLong(value);
  }

  // Ghi WARN tối đa mỗi phút một lần vì RedisRateLimiter đã nuốt lỗi và cho request qua
  private void warnRedisDown() {
    long now = System.nanoTime();
    long last = lastWarn.get();
    if (now - last >= WARN_EVERY_NANOS && lastWarn.compareAndSet(last, now)) {
      LOG.warn("Redis rate limiter unavailable, failing open (requests are not limited)");
    }
  }
}
