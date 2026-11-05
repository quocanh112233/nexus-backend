package com.nexus.identity.infrastructure.security;

import com.nexus.common.web.ProblemBodies;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

// Cấu hình bảo mật HTTP của identity-service: resource server JWT dùng khóa của chính Identity
@Configuration
public class SecurityConfig {

  private static final String[] PUBLIC_PATHS = {
    "/api/v1/auth/**",
    "/.well-known/jwks.json",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/swagger-ui.html",
    "/actuator/health/**"
  };

  private static final String[] NO_BEARER_PATHS = {"/api/v1/auth/**", "/.well-known/jwks.json"};

  private static final AuthenticationEntryPoint UNAUTHENTICATED =
      (request, response, ex) ->
          ProblemBodies.write(
              response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");

  private static final AccessDeniedHandler PERMISSION_DENIED =
      (request, response, ex) ->
          ProblemBodies.write(
              response, HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Permission denied");

  // Chuỗi lọc stateless: mở auth, JWKS, tài liệu API, health; còn lại cần Bearer JWT hợp lệ
  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            auth -> auth.requestMatchers(PUBLIC_PATHS).permitAll().anyRequest().authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .bearerTokenResolver(bearerTokenResolver())
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(UNAUTHENTICATED)
                    .accessDeniedHandler(PERMISSION_DENIED))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(UNAUTHENTICATED).accessDeniedHandler(PERMISSION_DENIED))
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .build();
  }

  // Bỏ qua Bearer trên auth và JWKS để token cũ không chặn login/refresh, nơi khác mặc định
  private static BearerTokenResolver bearerTokenResolver() {
    RequestMatcher noBearer =
        new OrRequestMatcher(
            Arrays.stream(NO_BEARER_PATHS)
                .map(p -> (RequestMatcher) PathPatternRequestMatcher.pathPattern(p))
                .toList());
    DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
    return request -> noBearer.matches(request) ? null : delegate.resolve(request);
  }

  // Decoder chỉ nhận RS256 ký bằng khóa của Identity, kiểm tra exp/nbf, iss và aud
  @Bean
  JwtDecoder jwtDecoder(
      JwtKeyProvider keys,
      @Value("${app.jwt.issuer}") String issuer,
      @Value("${app.jwt.audience}") String audience) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey(keys.publicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(
            new JwtIssuerValidator(issuer), new JwtAudienceValidator(audience)));
    return decoder;
  }
}
