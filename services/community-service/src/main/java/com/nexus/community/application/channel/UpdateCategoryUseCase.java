package com.nexus.community.application.channel;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.server.CategoryView;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.category.Category;
import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.category.CategoryName;
import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.Position;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case đổi tên và vị trí category, chỉ chủ server được phép
@Service
public class UpdateCategoryUseCase {

  private final ServerAccess access;
  private final CategoryRepository categoryRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public UpdateCategoryUseCase(
      ServerAccess access,
      CategoryRepository categoryRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.access = access;
    this.categoryRepository = categoryRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Áp tên, vị trí nếu có, lưu rồi chỉ phát CategoryUpdated cuối cùng dù đổi nhiều trường
  @Transactional
  public CategoryView execute(
      ServerId serverId,
      CategoryId categoryId,
      UserId userId,
      CategoryName name,
      Position position) {
    access.requireOwner(serverId, userId);
    Category category =
        categoryRepository
            .findById(categoryId)
            .filter(c -> c.serverId().equals(serverId))
            .orElseThrow(CategoryNotFoundException::new);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    if (name != null) {
      category.rename(name, now);
    }
    if (position != null) {
      category.moveTo(position, now);
    }
    List<DomainEvent> events = category.pullEvents();
    if (!events.isEmpty()) {
      categoryRepository.save(category);
      eventPublisher.publish(events.get(events.size() - 1));
    }
    return CategoryView.from(category);
  }
}
