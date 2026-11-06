package com.nexus.community.domain.category;

import com.nexus.community.domain.server.ServerId;
import java.util.List;
import java.util.Optional;

// Cổng lưu trữ aggregate Category
public interface CategoryRepository {

  // Lưu category
  void save(Category category);

  // Tìm category theo id
  Optional<Category> findById(CategoryId id);

  // Liệt kê category của server, sắp theo vị trí rồi id
  List<Category> findAllByServer(ServerId serverId);

  // Xóa category, kênh con được đặt category về null
  void delete(CategoryId id);
}
