package com.nexus.community.application.member;

import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.member.MemberSnapshot;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn duyệt toàn bộ thành viên mọi server theo từng lô keyset, đẩy từng bản ghi ra ngoài
@Service
public class StreamAllMembersQuery {

  static final int PAGE_SIZE = 1000;

  private final MemberRepository members;

  // Nhận cổng lưu trữ thành viên qua constructor
  public StreamAllMembersQuery(MemberRepository members) {
    this.members = members;
  }

  // Duyệt từng lô đến khi lô ngắn hơn kích thước trang, gọi sink cho từng thành viên
  @Transactional(readOnly = true)
  public void execute(Consumer<MemberSnapshot> sink) {
    ServerId afterServer = null;
    UserId afterUser = null;
    List<MemberSnapshot> batch;
    do {
      batch = members.pageAll(afterServer, afterUser, PAGE_SIZE);
      batch.forEach(sink);
      if (!batch.isEmpty()) {
        MemberSnapshot last = batch.get(batch.size() - 1);
        afterServer = new ServerId(last.serverId());
        afterUser = new UserId(last.userId());
      }
    } while (batch.size() == PAGE_SIZE);
  }
}
