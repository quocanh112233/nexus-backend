package com.nexus.identity.infrastructure.persistence.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data DAO cho bảng users, tham số tìm kiếm đã được chuẩn hóa chữ thường bởi value object
interface UserDao extends JpaRepository<UserJpaEntity, UUID> {

  // Tìm theo email đã chuẩn hóa
  Optional<UserJpaEntity> findByEmail(String email);

  // Tìm theo tên đăng nhập đã chuẩn hóa
  Optional<UserJpaEntity> findByUsername(String username);

  // Tìm theo danh sách id
  List<UserJpaEntity> findAllByIdIn(Collection<UUID> ids);
}
