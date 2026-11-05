package com.nexus.identity.interfaces.rest;

import com.nexus.common.web.ApiException;
import com.nexus.identity.application.user.ChangeAvatarUseCase;
import com.nexus.identity.application.user.GetProfileQuery;
import com.nexus.identity.application.user.IssueAvatarSignatureUseCase;
import com.nexus.identity.application.user.UpdateProfileUseCase;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Endpoint hồ sơ người dùng, tất cả cần Bearer
@RestController
@RequestMapping("/api/v1/users")
class UserController {

  private static final int MAX_BATCH = 100;
  private static final Pattern UUID_FORMAT =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private final GetProfileQuery getProfile;
  private final UpdateProfileUseCase updateProfile;
  private final IssueAvatarSignatureUseCase issueAvatarSignature;
  private final ChangeAvatarUseCase changeAvatar;

  // Nhận truy vấn hồ sơ và các use case qua constructor
  UserController(
      GetProfileQuery getProfile,
      UpdateProfileUseCase updateProfile,
      IssueAvatarSignatureUseCase issueAvatarSignature,
      ChangeAvatarUseCase changeAvatar) {
    this.getProfile = getProfile;
    this.updateProfile = updateProfile;
    this.issueAvatarSignature = issueAvatarSignature;
    this.changeAvatar = changeAvatar;
  }

  // Trả hồ sơ đầy đủ của người đang đăng nhập, userId lấy từ sub của JWT
  @GetMapping("/me")
  MeResponse me(@AuthenticationPrincipal Jwt jwt) {
    return MeResponse.from(getProfile.me(UUID.fromString(jwt.getSubject())));
  }

  // Cập nhật displayName và aboutMe được gửi, xung đột phiên bản thì 409
  @PatchMapping("/me")
  MeResponse updateMe(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
    return MeResponse.from(
        updateProfile.execute(
            UUID.fromString(jwt.getSubject()), request.displayName(), request.aboutMe()));
  }

  // Cấp chữ ký upload avatar cho folder của người gọi
  @PostMapping("/me/avatar/signature")
  AvatarSignatureResponse avatarSignature(@AuthenticationPrincipal Jwt jwt) {
    return AvatarSignatureResponse.from(
        issueAvatarSignature.execute(UUID.fromString(jwt.getSubject())));
  }

  // Đổi avatar sang publicId đã upload, publicId ngoài folder của người gọi thì 400
  @PutMapping("/me/avatar")
  MeResponse changeAvatar(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangeAvatarRequest request) {
    return MeResponse.from(
        changeAvatar.execute(UUID.fromString(jwt.getSubject()), request.publicId()));
  }

  // Tra nhiều hồ sơ công khai theo ids cách nhau dấu phẩy, tối đa 100, bỏ trùng, rỗng thì trả []
  @GetMapping
  List<PublicProfileResponse> batch(@RequestParam(name = "ids", defaultValue = "") String ids) {
    String[] tokens = ids.isEmpty() ? new String[0] : ids.split(",", -1);
    if (tokens.length > MAX_BATCH) {
      throw invalidIds("at most " + MAX_BATCH + " ids");
    }
    Set<UUID> unique = new LinkedHashSet<>();
    Arrays.stream(tokens).map(UserController::parseUuid).forEach(unique::add);
    return getProfile.byIds(unique).stream().map(PublicProfileResponse::from).toList();
  }

  // Trả hồ sơ công khai theo tên đăng nhập, không phân biệt hoa thường
  @GetMapping("/by-username/{username}")
  PublicProfileResponse byUsername(@PathVariable String username) {
    return PublicProfileResponse.from(getProfile.byUsername(username));
  }

  // Trả hồ sơ công khai theo id, id sai định dạng thì 400
  @GetMapping("/{userId}")
  PublicProfileResponse byId(@PathVariable UUID userId) {
    return PublicProfileResponse.from(getProfile.byId(userId));
  }

  // Đọc UUID chặt chẽ, sai định dạng thì 400 VALIDATION_FAILED
  private static UUID parseUuid(String token) {
    if (!UUID_FORMAT.matcher(token).matches()) {
      throw invalidIds("ids must be UUIDs");
    }
    return UUID.fromString(token);
  }

  // Dựng lỗi 400 VALIDATION_FAILED cho tham số ids
  private static ApiException invalidIds(String detail) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
  }
}
