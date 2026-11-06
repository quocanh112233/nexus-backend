package com.nexus.community.domain.server;

import com.nexus.community.domain.shared.UserId;
import java.util.List;
import java.util.Optional;

// Cổng lưu trữ aggregate Server cùng role @everyone
public interface ServerRepository {

  // Lưu server, lần đầu ghi cả role, các lần sau chỉ cập nhật hàng server
  void save(Server server);

  // Tìm server kèm role theo id
  Optional<Server> findById(ServerId id);

  // Xóa server, các bảng con xóa theo cascade
  void delete(ServerId id);

  // Liệt kê server mà user là thành viên, sắp theo thời điểm tạo rồi id
  List<Server> findAllByMember(UserId userId);
}
