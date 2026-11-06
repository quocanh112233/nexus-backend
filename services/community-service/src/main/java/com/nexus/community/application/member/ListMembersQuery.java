package com.nexus.community.application.member;

import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn trang thành viên của server theo keyset, chỉ thành viên được xem
@Service
public class ListMembersQuery {

  private final ServerAccess access;
  private final MemberRepository memberRepository;

  // Nhận cổng kiểm tra quyền và cổng lưu trữ qua constructor
  public ListMembersQuery(ServerAccess access, MemberRepository memberRepository) {
    this.access = access;
    this.memberRepository = memberRepository;
  }

  // Lấy limit+1 dòng sau afterUserId để biết còn trang sau, cắt về limit
  @Transactional(readOnly = true)
  public MemberPage execute(ServerId serverId, UserId caller, UserId afterUserId, int limit) {
    access.requireMember(serverId, caller);
    List<Member> rows = memberRepository.page(serverId, afterUserId, limit + 1);
    boolean hasMore = rows.size() > limit;
    List<MemberView> items = rows.stream().limit(limit).map(MemberView::from).toList();
    return new MemberPage(items, hasMore ? items.get(items.size() - 1).userId() : null);
  }
}
