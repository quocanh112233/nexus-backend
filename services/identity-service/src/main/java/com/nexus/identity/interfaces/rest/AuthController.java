package com.nexus.identity.interfaces.rest;

import com.nexus.common.web.ApiException;
import com.nexus.identity.application.auth.InvalidRefreshTokenException;
import com.nexus.identity.application.auth.LoginCommand;
import com.nexus.identity.application.auth.LoginResult;
import com.nexus.identity.application.auth.LoginUseCase;
import com.nexus.identity.application.auth.LogoutUseCase;
import com.nexus.identity.application.auth.RefreshResult;
import com.nexus.identity.application.auth.RefreshTokenUseCase;
import com.nexus.identity.application.auth.RegisterCommand;
import com.nexus.identity.application.auth.RegisterUserUseCase;
import com.nexus.identity.domain.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Endpoint xác thực công khai của identity-service
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

  private final RegisterUserUseCase registerUser;
  private final LoginUseCase login;
  private final RefreshTokenUseCase refresh;
  private final LogoutUseCase logout;
  private final RefreshCookies cookies;
  private final List<String> allowedOrigins;
  private final long accessTtlSeconds;

  // Nhận các use case xác thực, bộ dựng cookie, danh sách Origin cho phép và TTL access token
  AuthController(
      RegisterUserUseCase registerUser,
      LoginUseCase login,
      RefreshTokenUseCase refresh,
      LogoutUseCase logout,
      RefreshCookies cookies,
      @Value("${app.cors.allowed-origins}") List<String> allowedOrigins,
      @Value("${app.jwt.access-ttl}") Duration accessTtl) {
    this.registerUser = registerUser;
    this.login = login;
    this.refresh = refresh;
    this.logout = logout;
    this.cookies = cookies;
    this.allowedOrigins = allowedOrigins;
    this.accessTtlSeconds = accessTtl.toSeconds();
  }

  // Đăng ký tài khoản mới, trả 201 kèm Location tới user
  @PostMapping("/register")
  ResponseEntity<MeResponse> register(@Valid @RequestBody RegisterRequest request) {
    User user =
        registerUser.execute(
            new RegisterCommand(
                request.email(), request.username(), request.displayName(), request.password()));
    return ResponseEntity.created(URI.create("/api/v1/users/" + user.id().value()))
        .body(MeResponse.from(user));
  }

  // Đăng nhập bằng email và mật khẩu, trả access token Bearer và đặt cookie refresh token
  @PostMapping("/login")
  ResponseEntity<LoginResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    LoginResult result =
        login.execute(
            new LoginCommand(
                request.email(),
                request.password(),
                http.getHeader(HttpHeaders.USER_AGENT),
                http.getRemoteAddr()));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.issue(result.refreshTokenRaw()))
        .body(new LoginResponse(result.accessToken().value(), "Bearer", accessTtlSeconds));
  }

  // Đổi cookie refresh token lấy access token mới và cookie mới, Origin lạ thì 403
  @PostMapping("/refresh")
  ResponseEntity<LoginResponse> refresh(
      @CookieValue(name = RefreshCookies.NAME, required = false) String rawToken,
      @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
      HttpServletRequest http) {
    if (origin != null && !allowedOrigins.contains(origin)) {
      throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Origin not allowed");
    }
    if (rawToken == null || rawToken.isBlank()) {
      throw new InvalidRefreshTokenException();
    }
    RefreshResult result =
        refresh.execute(rawToken, http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.issue(result.refreshTokenRaw()))
        .body(new LoginResponse(result.accessToken().value(), "Bearer", accessTtlSeconds));
  }

  // Thu hồi refresh token trong cookie nếu có, luôn trả 204 và xóa cookie
  @PostMapping("/logout")
  ResponseEntity<Void> logout(
      @CookieValue(name = RefreshCookies.NAME, required = false) String rawToken) {
    logout.execute(rawToken);
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
  }
}
