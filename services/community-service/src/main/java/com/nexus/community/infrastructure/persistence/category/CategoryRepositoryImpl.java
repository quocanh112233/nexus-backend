package com.nexus.community.infrastructure.persistence.category;

import com.nexus.community.domain.category.Category;
import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.server.ServerId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// Cài đặt CategoryRepository bằng CategoryDao và CategoryMapper
@Repository
class CategoryRepositoryImpl implements CategoryRepository {

  private final CategoryDao dao;
  private final CategoryMapper mapper;

  // Nhận DAO và mapper qua constructor
  CategoryRepositoryImpl(CategoryDao dao, CategoryMapper mapper) {
    this.dao = dao;
    this.mapper = mapper;
  }

  // Lưu và flush ngay
  @Override
  public void save(Category category) {
    dao.saveAndFlush(mapper.toJpa(category));
  }

  // Tìm category theo id
  @Override
  public Optional<Category> findById(CategoryId id) {
    return dao.findById(id.value()).map(mapper::toDomain);
  }

  // Liệt kê category của server theo position rồi id
  @Override
  public List<Category> findAllByServer(ServerId serverId) {
    return dao.findAllByServerIdOrderByPositionAscIdAsc(serverId.value()).stream()
        .map(mapper::toDomain)
        .toList();
  }

  // Xóa category theo id
  @Override
  public void delete(CategoryId id) {
    dao.deleteById(id.value());
  }
}
