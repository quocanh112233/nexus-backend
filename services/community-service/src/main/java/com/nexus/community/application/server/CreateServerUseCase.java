package com.nexus.community.application.server;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.domain.category.Category;
import com.nexus.community.domain.category.CategoryName;
import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelName;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.Position;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case tạo server kèm category "Text Channels", kênh "general" và thành viên chủ server
@Service
public class CreateServerUseCase {

  private final ServerRepository serverRepository;
  private final CategoryRepository categoryRepository;
  private final ChannelRepository channelRepository;
  private final MemberRepository memberRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận các cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public CreateServerUseCase(
      ServerRepository serverRepository,
      CategoryRepository categoryRepository,
      ChannelRepository channelRepository,
      MemberRepository memberRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.serverRepository = serverRepository;
    this.categoryRepository = categoryRepository;
    this.channelRepository = channelRepository;
    this.memberRepository = memberRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Tạo bốn aggregate rồi ghi bốn event theo thứ tự server, category, channel, member
  @Transactional
  public ServerDetail execute(CreateServerCommand command) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Server server = Server.create(new ServerName(command.name()), command.owner(), now);
    Category category =
        Category.create(server.id(), new CategoryName("Text Channels"), new Position(0), now);
    Channel channel =
        Channel.create(
            server.id(), category.id(), new ChannelName("general"), new Position(0), now);
    Member owner = Member.join(server.id(), command.owner(), now);

    serverRepository.save(server);
    categoryRepository.save(category);
    channelRepository.save(channel);
    memberRepository.addIfAbsent(owner);

    List<DomainEvent> events = new ArrayList<>(server.pullEvents());
    events.addAll(category.pullEvents());
    events.addAll(channel.pullEvents());
    events.addAll(owner.pullEvents());
    events.forEach(eventPublisher::publish);

    return new ServerDetail(
        ServerSummary.from(server),
        List.of(CategoryView.from(category)),
        List.of(ChannelView.from(channel)),
        server.roles().stream().map(RoleView::from).toList());
  }
}
