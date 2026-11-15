package com.nexus.community.domain.member;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import java.util.Optional;

// Cổng lưu trữ aggregate Member
public interface MemberRepository {

  // Thêm thành viên nếu chưa có, true khi thực sự thêm hàng mới
  boolean addIfAbsent(Member member);

  // Ghi lại tập role của thành viên bằng xóa-chèn trong transaction của nơi gọi
  void save(Member member);

  // Tìm thành viên theo server và user
  Optional<Member> find(ServerId serverId, UserId userId);

  // Tìm thành viên và khóa hàng của họ đến hết transaction, để các lần ghi role tuần tự hóa
  Optional<Member> findForUpdate(ServerId serverId, UserId userId);

  // Gỡ thành viên khỏi server
  void remove(ServerId serverId, UserId userId);

  // Đếm thành viên của server
  long count(ServerId serverId);

  // Lấy một trang thành viên theo user_id tăng dần, afterUserId null là trang đầu
  List<Member> page(ServerId serverId, UserId afterUserId, int limit);

  // Lấy một lô thành viên mọi server theo (server_id, user_id) tăng dần, null là từ đầu
  List<MemberSnapshot> pageAll(ServerId afterServer, UserId afterUser, int limit);
}
