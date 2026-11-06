package com.nexus.community.infrastructure.security;

import com.nexus.common.web.ProblemBodies;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.client.RestTemplate;

// Cấu hình bảo mật HTTP của community-service: resource server JWT xác thực qua JWKS của Identity
@Configuration
public class SecurityConfig {

  private static final String[] PUBLIC_PATHS = {
    "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health/**"
  };

  private static final AuthenticationEntryPoint UNAUTHENTICATED =
      (request, response, ex) ->
          ProblemBodies.write(
              response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");

  private static final AccessDeniedHandler PERMISSION_DENIED =
      (request, response, ex) ->
          ProblemBodies.write(
              response, HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Permission denied");

  // Chuỗi lọc stateless: mở tài liệu API, health, xem lời mời; còn lại cần Bearer JWT hợp lệ
  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(PUBLIC_PATHS)
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/invites/*")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
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

  // Bỏ qua Bearer khi xem trước lời mời để token cũ hay rác không chặn trang công khai
  private static BearerTokenResolver bearerTokenResolver() {
    RequestMatcher preview =
        PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/invites/*");
    DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
    return request -> preview.matches(request) ? null : delegate.resolve(request);
  }

  // Decoder lấy khóa JWKS qua RestTemplate có timeout, chỉ nhận RS256, kiểm tra exp/nbf, iss, aud
  @Bean
  JwtDecoder jwtDecoder(
      @Value("${app.jwt.jwks-uri}") String jwksUri,
      @Value("${app.jwt.issuer}") String issuer,
      @Value("${app.jwt.audience}") String audience,
      @Value("${app.jwt.jwks-connect-timeout}") Duration connectTimeout,
      @Value("${app.jwt.jwks-read-timeout}") Duration readTimeout) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(connectTimeout);
    requestFactory.setReadTimeout(readTimeout);
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwksUri)
            .restOperations(new RestTemplate(requestFactory))
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(
            new JwtIssuerValidator(issuer), new JwtAudienceValidator(audience)));
    return decoder;
  }
}
