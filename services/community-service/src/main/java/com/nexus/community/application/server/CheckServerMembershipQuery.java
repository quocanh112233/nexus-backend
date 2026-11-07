package com.nexus.community.application.server;

import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn user có phải thành viên của server không
@Service
public class CheckServerMembershipQuery {

  private final MemberRepository memberRepository;

  // Nhận cổng lưu trữ thành viên qua constructor
  public CheckServerMembershipQuery(MemberRepository memberRepository) {
    this.memberRepository = memberRepository;
  }

  // True khi user là thành viên của server
  @Transactional(readOnly = true)
  public boolean execute(UserId userId, ServerId serverId) {
    return memberRepository.find(serverId, userId).isPresent();
  }
}
