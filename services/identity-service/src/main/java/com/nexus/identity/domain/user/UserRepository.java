package com.nexus.identity.domain.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// Cổng lưu trữ aggregate User
public interface UserRepository {

  // Lưu user, ném EmailAlreadyTakenException hoặc UsernameAlreadyTakenException khi trùng
  User save(User user);

  // Tìm user theo id
  Optional<User> findById(UserId id);

  // Tìm user theo email
  Optional<User> findByEmail(Email email);

  // Tìm user theo tên đăng nhập
  Optional<User> findByUsername(Username username);

  // Tìm nhiều user theo danh sách id
  List<User> findAllByIds(Collection<UserId> ids);
}
