package com.nexus.identity.infrastructure.persistence.token;

import com.nexus.identity.domain.token.RefreshToken;
import com.nexus.identity.domain.token.RefreshTokenRepository;
import com.nexus.identity.domain.token.TokenHash;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

// Cài đặt RefreshTokenRepository bằng RefreshTokenDao và RefreshTokenMapper
@Repository
class RefreshTokenRepositoryImpl implements RefreshTokenRepository {

  private final RefreshTokenDao dao;
  private final RefreshTokenMapper mapper;

  // Nhận DAO và mapper qua constructor
  RefreshTokenRepositoryImpl(RefreshTokenDao dao, RefreshTokenMapper mapper) {
    this.dao = dao;
    this.mapper = mapper;
  }

  // Lưu và flush ngay, trả token đã lưu
  @Override
  public RefreshToken save(RefreshToken token) {
    return mapper.toDomain(dao.saveAndFlush(mapper.toJpa(token)));
  }

  // Tìm token theo chuỗi băm
  @Override
  public Optional<RefreshToken> findByHash(TokenHash hash) {
    return dao.findByTokenHash(hash.value()).map(mapper::toDomain);
  }

  // Thu hồi nguyên tử một token còn hoạt động, true khi đúng một dòng bị đổi
  @Override
  public boolean revokeIfActive(UUID id, UUID replacedById, Instant now) {
    return dao.revokeIfActive(id, replacedById, now) == 1;
  }

  // Thu hồi mọi token chưa thu hồi của family
  @Override
  public int revokeFamily(UUID familyId, Instant now) {
    return dao.revokeFamily(familyId, now);
  }
}
