package com.nexus.gateway;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import io.netty.channel.ChannelOption;
import java.text.ParseException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

// Cấu hình bảo mật của gateway: tự verify JWT qua JWKS của Identity trước khi chuyển tiếp
@Configuration
public class SecurityConfig {

  private static final ServerWebExchangeMatcher PUBLIC =
      new OrServerWebExchangeMatcher(
          ServerWebExchangeMatchers.pathMatchers(HttpMethod.OPTIONS, "/**"),
          ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, "/api/v1/invites/*"),
          ServerWebExchangeMatchers.pathMatchers(
              "/api/v1/auth/**",
              "/docs/**",
              "/swagger-ui*",
              "/swagger-ui/**",
              "/webjars/**",
              "/v3/api-docs/**",
              "/actuator/health/**"));

  private static final ServerAuthenticationEntryPoint UNAUTHENTICATED =
      (exchange, ex) ->
          GatewayErrorHandler.write(
              exchange,
              HttpStatus.UNAUTHORIZED,
              "UNAUTHENTICATED",
              "Authentication required",
              null);

  private static final ServerAccessDeniedHandler PERMISSION_DENIED =
      (exchange, ex) ->
          GatewayErrorHandler.write(
              exchange, HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Permission denied", null);

  // Nguồn CORS duy nhất lấy từ globalcors của gateway để cả 401/404 cũng có header CORS
  @Bean
  CorsConfigurationSource corsConfigurationSource(GlobalCorsProperties properties) {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.setCorsConfigurations(properties.getCorsConfigurations());
    return source;
  }

  // Chuỗi lọc stateless: mở đường công khai và mọi OPTIONS, còn lại cần Bearer JWT hợp lệ
  @Bean
  SecurityWebFilterChain filterChain(ServerHttpSecurity http, ReactiveJwtDecoder decoder) {
    return http.authorizeExchange(
            auth -> auth.matchers(PUBLIC).permitAll().anyExchange().authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .bearerTokenConverter(bearerTokenConverter())
                    .jwt(jwt -> jwt.jwtDecoder(decoder))
                    .authenticationEntryPoint(UNAUTHENTICATED)
                    .accessDeniedHandler(PERMISSION_DENIED))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(UNAUTHENTICATED).accessDeniedHandler(PERMISSION_DENIED))
        .cors(Customizer.withDefaults())
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .build();
  }

  // Bỏ qua Bearer trên đường công khai để token cũ hay rác không gây 401, header vẫn được chuyển
  private static ServerAuthenticationConverter bearerTokenConverter() {
    ServerBearerTokenAuthenticationConverter delegate =
        new ServerBearerTokenAuthenticationConverter();
    return exchange ->
        PUBLIC
            .matches(exchange)
            .flatMap(match -> match.isMatch() ? Mono.empty() : delegate.convert(exchange));
  }

  // Decoder lấy khóa JWKS qua WebClient có timeout, chỉ nhận RS256, kiểm tra exp/nbf, iss, aud
  @Bean
  ReactiveJwtDecoder jwtDecoder(
      @Value("${app.jwt.jwks-uri}") String jwksUri,
      @Value("${app.jwt.jwks-connect-timeout}") Duration connectTimeout,
      @Value("${app.jwt.jwks-response-timeout}") Duration responseTimeout,
      @Value("${app.jwt.jwks-refresh-cooldown}") Duration cooldown,
      @Value("${app.jwt.issuer}") String issuer,
      @Value("${app.jwt.audience}") String audience) {
    HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
            .responseTimeout(responseTimeout);
    WebClient client =
        WebClient.builder()
            .baseUrl(jwksUri)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    NimbusReactiveJwtDecoder decoder =
        NimbusReactiveJwtDecoder.withJwkSource(new JwksCache(client, cooldown)::select)
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(
            new JwtIssuerValidator(issuer), new JwtAudienceValidator(audience)));
    return decoder;
  }

  // Cache JWKS: kid lạ chỉ tải lại khi lần tải thành công gần nhất đã quá cooldown
  private static final class JwksCache {

    private final WebClient client;
    private final long cooldownNanos;
    private final AtomicReference<JWKSet> keys = new AtomicReference<>(new JWKSet());
    private volatile long fetchedAt;

    // Nhận WebClient trỏ vào JWKS và khoảng nghỉ tối thiểu giữa hai lần tải lại
    private JwksCache(WebClient client, Duration cooldown) {
      this.client = client;
      this.cooldownNanos = cooldown.toNanos();
      this.fetchedAt = System.nanoTime() - cooldownNanos - 1;
    }

    // Chọn khóa từ cache; kid lạ hết cooldown thì tải JWKS, có thể song song (xem journal.md)
    private Flux<JWK> select(SignedJWT jwt) {
      JWKSelector selector = new JWKSelector(JWKMatcher.forJWSHeader(jwt.getHeader()));
      List<JWK> cached = selector.select(keys.get());
      if (!cached.isEmpty() || System.nanoTime() - fetchedAt < cooldownNanos) {
        return Flux.fromIterable(cached);
      }
      return client
          .get()
          .retrieve()
          .bodyToMono(String.class)
          .handle(
              (body, sink) -> {
                try {
                  sink.next(JWKSet.parse(body));
                } catch (ParseException e) {
                  sink.error(e);
                }
              })
          .cast(JWKSet.class)
          .doOnNext(
              set -> {
                keys.set(set);
                fetchedAt = System.nanoTime();
              })
          .flatMapIterable(selector::select);
    }
  }
}
