package com.nexus.identity.infrastructure.security;

import com.nimbusds.jose.jwk.JWKSet;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Công bố public JWKS để service khác verify access token của Identity
@RestController
class JwksController {

  private final Map<String, Object> publicJwks;

  // Dựng sẵn JWKS chỉ gồm phần công khai của khóa ký
  JwksController(JwtKeyProvider keys) {
    this.publicJwks = new JWKSet(keys.rsaKey()).toPublicJWKSet().toJSONObject();
  }

  // Trả JWKS công khai dạng application/json
  @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> jwks() {
    return publicJwks;
  }
}
