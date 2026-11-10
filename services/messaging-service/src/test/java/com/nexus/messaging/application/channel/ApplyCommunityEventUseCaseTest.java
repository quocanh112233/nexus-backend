package com.nexus.messaging.application.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra ApplyCommunityEventUseCase gọi đúng repository và phiên bản quyền theo từng loại event
class ApplyCommunityEventUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  private final UUID serverId = UUID.randomUUID();
  private final UUID channelId = UUID.randomUUID();
  private final List<String> calls = new ArrayList<>();
  private boolean first = true;
  private final ApplyCommunityEventUseCase useCase =
      new ApplyCommunityEventUseCase(
          eventId -> first,
          new FakeRefs(),
          server -> calls.add("bump:" + server),
          Clock.fixed(NOW, ZoneOffset.UTC));

  // Kịch bản: kênh bị xóa
  // Input: event ChannelDeleted lần đầu
  // Output: tombstone kênh với thời điểm hiện tại rồi bump, đúng hai lời gọi
  @Test
  void channelDeletedTombstonesThenBumps() {
    apply("ChannelDeleted");

    assertThat(calls)
        .containsExactly("tombstoneChannel:" + channelId + ":" + serverId + ":" + NOW, bump());
  }

  // Kịch bản: server bị xóa
  // Input: event ServerDeleted lần đầu
  // Output: tombstone server với thời điểm hiện tại rồi bump, đúng hai lời gọi
  @Test
  void serverDeletedTombstonesServerThenBumps() {
    apply("ServerDeleted");

    assertThat(calls).containsExactly("tombstoneServer:" + serverId + ":" + NOW, bump());
  }

  // Kịch bản: kênh mới tạo
  // Input: event ChannelCreated lần đầu
  // Output: chỉ upsert kênh server, không bump
  @Test
  void channelCreatedOnlyUpserts() {
    apply("ChannelCreated");

    assertThat(calls).containsExactly("upsert:" + channelId + ":" + serverId);
  }

  // Kịch bản: 7 loại event làm đổi quyền
  // Input: từng eventType trong bảng ChannelPermissionsChanged..RoleDeleted
  // Output: mỗi loại chỉ gọi đúng một lần bump
  @Test
  void permissionEventsOnlyBump() {
    for (String type :
        List.of(
            "ChannelPermissionsChanged",
            "MemberLeft",
            "MemberKicked",
            "MemberBanned",
            "MemberRolesChanged",
            "RoleUpdated",
            "RoleDeleted")) {
      calls.clear();

      apply(type);

      assertThat(calls).as(type).containsExactly(bump());
    }
  }

  // Kịch bản: loại event không xử lý
  // Input: eventType Whatever
  // Output: không gọi gì
  @Test
  void unknownTypeDoesNothing() {
    apply("Whatever");

    assertThat(calls).isEmpty();
  }

  // Kịch bản: event đã xử lý
  // Input: firstDelivery trả false cho ChannelDeleted
  // Output: không gọi gì
  @Test
  void duplicateEventIdIsIgnored() {
    first = false;

    apply("ChannelDeleted");

    assertThat(calls).isEmpty();
  }

  // Kịch bản: bump chạy sau cùng để tombstone đã ghi trước khi version tăng
  // Input: ServerDeleted và ChannelDeleted lần đầu
  // Output: lời gọi cuối cùng của mỗi event là bump
  @Test
  void bumpIsLastCall() {
    for (String type : List.of("ServerDeleted", "ChannelDeleted")) {
      calls.clear();

      apply(type);

      assertThat(calls.getLast()).isEqualTo(bump());
    }
  }

  // Chạy use case với event lần đầu của loại type
  private void apply(String type) {
    useCase.execute(new CommunityEvent(UUID.randomUUID(), type, serverId, channelId));
  }

  // Lời gọi bump mong đợi cho serverId của test
  private String bump() {
    return "bump:" + serverId;
  }

  // Repository giả ghi lại lời gọi vào danh sách chung
  private final class FakeRefs implements ChannelRefRepository {

    // Ghi lời gọi upsert
    @Override
    public void upsertServerChannel(ChannelId channel, UUID server) {
      calls.add("upsert:" + channel.value() + ":" + server);
    }

    // Không dùng trong test này
    @Override
    public void upsertDmChannel(ChannelId channel) {}

    // Ghi lời gọi tombstone kênh
    @Override
    public void tombstoneChannel(ChannelId channel, UUID server, Instant at) {
      calls.add("tombstoneChannel:" + channel.value() + ":" + server + ":" + at);
    }

    // Ghi lời gọi tombstone server
    @Override
    public void tombstoneServer(UUID server, Instant at) {
      calls.add("tombstoneServer:" + server + ":" + at);
    }

    // Luôn trả chưa xóa
    @Override
    public boolean isDeleted(ChannelId channel) {
      return false;
    }

    // Không dùng trong test này
    @Override
    public boolean isDm(ChannelId channel) {
      return false;
    }
  }
}
