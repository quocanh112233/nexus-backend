package com.nexus.community.domain.invite;

import com.nexus.community.domain.server.ServerId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

// Cổng lưu trữ aggregate Invite
public interface InviteRepository {

  // Thêm lời mời mới bằng INSERT, mã đã tồn tại thì ném DataIntegrityViolationException
  void add(Invite invite);

  // Cập nhật lời mời đã có, hiện chỉ ghi revoked_at
  void save(Invite invite);

  // Tìm lời mời theo mã
  Optional<Invite> findByCode(InviteCode code);

  // Liệt kê lời mời của server, mới tạo trước
  List<Invite> findAllByServer(ServerId serverId);

  // Tăng uses bằng một câu UPDATE có điều kiện, true khi lời mời còn dùng được và đã tăng
  boolean tryConsume(InviteCode code, Instant now);
}
