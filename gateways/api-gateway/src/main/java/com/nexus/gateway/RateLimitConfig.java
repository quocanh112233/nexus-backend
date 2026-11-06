package com.nexus.gateway;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

// Bean rate limit: bộ giới hạn Redis thay mặc định của gateway và khóa theo IP kết nối trực tiếp
@Configuration
public class RateLimitConfig {

  // Bộ giới hạn token bucket trên Redis, app.ratelimit.enabled=false thì không chạm Redis
  @Bean
  RedisRateLimiter gatewayRateLimiter(
      ReactiveStringRedisTemplate redisTemplate,
      @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script,
      ConfigurationService configurationService,
      @Value("${app.ratelimit.enabled}") boolean enabled) {
    return new GatewayRateLimiter(redisTemplate, script, configurationService, enabled);
  }

  // Khóa là IP của kết nối TCP, bỏ qua X-Forwarded-For vì client tự giả được; proxy xử lý ở P9
  @Bean
  @Primary
  KeyResolver ipKeyResolver() {
    return exchange ->
        Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
            .map(address -> address.getAddress().getHostAddress());
  }

  // Khóa là claim sub của JWT đã verify; thiếu hay rỗng thì không có khóa và route từ chối
  @Bean
  KeyResolver subKeyResolver() {
    return exchange ->
        exchange
            .getPrincipal()
            .filter(JwtAuthenticationToken.class::isInstance)
            .flatMap(
                principal ->
                    Mono.justOrEmpty(((JwtAuthenticationToken) principal).getToken().getSubject()))
            .filter(subject -> !subject.isBlank());
  }
}
