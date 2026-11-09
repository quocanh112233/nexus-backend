package com.nexus.realtime.infrastructure.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

// Dựng JwtDecoder cho lúc CONNECT, không có filter HTTP nên /ws không bị Spring Security chặn
@Configuration
public class JwtDecoderConfig {

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
