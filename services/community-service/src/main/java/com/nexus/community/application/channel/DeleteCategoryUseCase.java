package com.nexus.community.application.channel;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.category.Category;
import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case xóa category, kênh con về không category nhờ DB, cần quyền MANAGE_CHANNELS
@Service
public class DeleteCategoryUseCase {

  private final ServerAccess access;
  private final CategoryRepository categoryRepository;
  private final DomainEventPublisher eventPublisher;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ và cổng phát sự kiện qua constructor
  public DeleteCategoryUseCase(
      ServerAccess access,
      CategoryRepository categoryRepository,
      DomainEventPublisher eventPublisher) {
    this.access = access;
    this.categoryRepository = categoryRepository;
    this.eventPublisher = eventPublisher;
  }

  // Phát CategoryDeleted rồi xóa category
  @Transactional
  public void execute(ServerId serverId, CategoryId categoryId, UserId userId) {
    access.requirePermission(serverId, userId, Permission.MANAGE_CHANNELS);
    Category category =
        categoryRepository
            .findById(categoryId)
            .filter(c -> c.serverId().equals(serverId))
            .orElseThrow(CategoryNotFoundException::new);
    category.delete();
    category.pullEvents().forEach(eventPublisher::publish);
    categoryRepository.delete(categoryId);
  }
}
