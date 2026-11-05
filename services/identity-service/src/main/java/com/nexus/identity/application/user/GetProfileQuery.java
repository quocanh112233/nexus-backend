package com.nexus.identity.application.user;

import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.UserRepository;
import com.nexus.identity.domain.user.Username;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn hồ sơ: của chính mình, công khai theo id, theo tên đăng nhập hoặc theo lô id
@Service
public class GetProfileQuery {

  private final UserRepository userRepository;

  // Nhận kho user qua constructor
  public GetProfileQuery(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  // Lấy user đầy đủ của chính chủ, không có thì ném UserNotFoundException
  @Transactional(readOnly = true)
  public User me(UUID userId) {
    return userRepository.findById(new UserId(userId)).orElseThrow(UserNotFoundException::new);
  }

  // Lấy hồ sơ công khai theo id, không có thì ném UserNotFoundException
  @Transactional(readOnly = true)
  public PublicProfile byId(UUID userId) {
    return PublicProfile.from(me(userId));
  }

  // Lấy hồ sơ công khai theo tên đăng nhập không phân biệt hoa thường
  @Transactional(readOnly = true)
  public PublicProfile byUsername(String username) {
    return userRepository
        .findByUsername(new Username(username))
        .map(PublicProfile::from)
        .orElseThrow(UserNotFoundException::new);
  }

  // Lấy hồ sơ công khai của các id tồn tại, bỏ qua id lạ
  @Transactional(readOnly = true)
  public List<PublicProfile> byIds(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return userRepository.findAllByIds(ids.stream().map(UserId::new).toList()).stream()
        .map(PublicProfile::from)
        .toList();
  }
}
