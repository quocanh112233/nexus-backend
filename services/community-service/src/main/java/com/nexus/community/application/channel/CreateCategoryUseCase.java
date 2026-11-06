package com.nexus.community.application.channel;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.server.CategoryView;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.category.Category;
import com.nexus.community.domain.category.CategoryName;
import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.Position;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case tạo category, chỉ chủ server được phép
@Service
public class CreateCategoryUseCase {

  private final ServerAccess access;
  private final CategoryRepository categoryRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public CreateCategoryUseCase(
      ServerAccess access,
      CategoryRepository categoryRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.access = access;
    this.categoryRepository = categoryRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Tạo category, vị trí mặc định là số category hiện có, lưu rồi phát CategoryCreated
  @Transactional
  public CategoryView execute(
      ServerId serverId, UserId userId, CategoryName name, Integer position) {
    access.requireOwner(serverId, userId);
    Position at =
        new Position(
            position != null ? position : categoryRepository.findAllByServer(serverId).size());
    Category category =
        Category.create(serverId, name, at, clock.instant().truncatedTo(ChronoUnit.MICROS));
    categoryRepository.save(category);
    category.pullEvents().forEach(eventPublisher::publish);
    return CategoryView.from(category);
  }
}
