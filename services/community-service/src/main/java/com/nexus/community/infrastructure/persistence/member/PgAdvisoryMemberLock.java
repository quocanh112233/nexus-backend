package com.nexus.community.infrastructure.persistence.member;

import com.nexus.community.application.port.MemberLock;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

// Cài đặt MemberLock bằng pg_advisory_xact_lock, chỉ dùng trong transaction (nhả khi commit)
@Component
class PgAdvisoryMemberLock implements MemberLock {

  private final EntityManager entityManager;

  // Nhận EntityManager dùng chung transaction của use case
  PgAdvisoryMemberLock(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  // Lấy khóa advisory theo hash của serverId:userId, chờ tới khi có, ngoài transaction thì ném lỗi
  @Override
  public void lock(ServerId serverId, UserId userId) {
    Assert.state(
        TransactionSynchronizationManager.isActualTransactionActive(),
        "MemberLock requires an active transaction");
    entityManager
        .createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .setParameter("key", serverId.value() + ":" + userId.value())
        .getResultList();
  }
}
