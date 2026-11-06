package com.nexus.community.application.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.domain.category.CategoryCreated;
import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.channel.ChannelCreated;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.member.MemberJoined;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.ServerCreated;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

// Kiểm tra CreateServerUseCase dựng đủ bốn aggregate và phát bốn event đúng thứ tự
class CreateServerUseCaseTest {

  private final ServerRepository servers = mock(ServerRepository.class);
  private final CategoryRepository categories = mock(CategoryRepository.class);
  private final ChannelRepository channels = mock(ChannelRepository.class);
  private final MemberRepository members = mock(MemberRepository.class);
  private final List<DomainEvent> published = new ArrayList<>();
  private final DomainEventPublisher publisher = published::add;
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-11-05T01:02:03.123456789Z"), ZoneOffset.UTC);
  private final CreateServerUseCase useCase =
      new CreateServerUseCase(servers, categories, channels, members, publisher, clock);

  // Kịch bản: tạo server sinh category, kênh, thành viên chủ và bốn event theo thứ tự
  // Input: owner mới và tên "  My Server  "
  // Output: lưu đủ bốn aggregate, phát đúng thứ tự Server, Category, Channel, Member
  @Test
  void createsFourAggregatesAndFourEventsInOrder() {
    UserId owner = UserId.newId();

    ServerDetail detail = useCase.execute(new CreateServerCommand(owner, "  My Server  "));

    verify(servers).save(org.mockito.ArgumentMatchers.any());
    verify(categories).save(org.mockito.ArgumentMatchers.any());
    verify(channels).save(org.mockito.ArgumentMatchers.any());
    verify(members).addIfAbsent(org.mockito.ArgumentMatchers.any());
    assertThat(published)
        .hasSize(4)
        .satisfies(
            e -> {
              assertThat(e.get(0)).isInstanceOf(ServerCreated.class);
              assertThat(e.get(1)).isInstanceOf(CategoryCreated.class);
              assertThat(e.get(2)).isInstanceOf(ChannelCreated.class);
              assertThat(e.get(3)).isInstanceOf(MemberJoined.class);
            });
    assertThat(detail.server().name()).isEqualTo("My Server");
    assertThat(detail.server().ownerId()).isEqualTo(owner.value());
    assertThat(detail.server().createdAt()).isEqualTo(Instant.parse("2026-11-05T01:02:03.123456Z"));
    assertThat(detail.categories()).extracting(CategoryView::name).containsExactly("Text Channels");
    assertThat(detail.channels()).extracting(ChannelView::name).containsExactly("general");
    assertThat(detail.channels().get(0).categoryId()).isEqualTo(detail.categories().get(0).id());
    assertThat(detail.roles()).extracting(RoleView::name).containsExactly("@everyone");
  }
}
