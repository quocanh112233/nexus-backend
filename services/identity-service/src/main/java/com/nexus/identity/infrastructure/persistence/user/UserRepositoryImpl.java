package com.nexus.identity.infrastructure.persistence.user;

import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.EmailAlreadyTakenException;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.UserRepository;
import com.nexus.identity.domain.user.Username;
import com.nexus.identity.domain.user.UsernameAlreadyTakenException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

// Cài đặt UserRepository bằng UserDao và UserMapper, dịch lỗi unique sang lỗi miền
@Repository
class UserRepositoryImpl implements UserRepository {

  private static final String EMAIL_CONSTRAINT = "ux_users_email";
  private static final String USERNAME_CONSTRAINT = "ux_users_username";

  private final UserDao dao;
  private final UserMapper mapper;

  // Nhận DAO và mapper qua constructor
  UserRepositoryImpl(UserDao dao, UserMapper mapper) {
    this.dao = dao;
    this.mapper = mapper;
  }

  // Lưu và flush ngay để lỗi unique nổi lên, trả aggregate mang version mới
  @Override
  public User save(User user) {
    try {
      return mapper.toDomain(dao.saveAndFlush(mapper.toJpa(user)));
    } catch (DataIntegrityViolationException ex) {
      String constraint = constraintName(ex);
      if (EMAIL_CONSTRAINT.equals(constraint)) {
        throw new EmailAlreadyTakenException(user.email());
      }
      if (USERNAME_CONSTRAINT.equals(constraint)) {
        throw new UsernameAlreadyTakenException(user.username());
      }
      throw ex;
    }
  }

  // Tìm user theo id
  @Override
  public Optional<User> findById(UserId id) {
    return dao.findById(id.value()).map(mapper::toDomain);
  }

  // Tìm user theo email
  @Override
  public Optional<User> findByEmail(Email email) {
    return dao.findByEmail(email.value()).map(mapper::toDomain);
  }

  // Tìm user theo tên đăng nhập
  @Override
  public Optional<User> findByUsername(Username username) {
    return dao.findByUsername(username.value()).map(mapper::toDomain);
  }

  // Tìm nhiều user theo danh sách id
  @Override
  public List<User> findAllByIds(Collection<UserId> ids) {
    return dao.findAllByIdIn(ids.stream().map(UserId::value).toList()).stream()
        .map(mapper::toDomain)
        .toList();
  }

  // Duyệt chuỗi cause lấy tên constraint bị vi phạm, null nếu không có
  private static String constraintName(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
        return cve.getConstraintName();
      }
    }
    return null;
  }
}
