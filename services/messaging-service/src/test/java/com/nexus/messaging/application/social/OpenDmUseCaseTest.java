package com.nexus.messaging.application.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.social.OpenDmUseCase.OpenDmResult;
import com.nexus.messaging.application.social.SocialFakes.FakeFriendships;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.DmChannel;
import com.nexus.messaging.domain.social.DmChannelCreated;
import com.nexus.messaging.domain.social.DmChannelRepository;
import com.nexus.messaging.domain.social.NotFriendsException;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra mở và liệt kê DM: chỉ bạn bè, idempotent, thua đua không phát sự kiện
class OpenDmUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private final UserId me = new UserId(UUID.randomUUID());
  private final UserId friend = new UserId(UUID.randomUUID());
  private final UserPair pair = UserPair.of(me, friend);
  private final FakeFriendships friendships = new FakeFriendships();
  private final FakeDms dms = new FakeDms();
  private final FakeRefs refs = new FakeRefs();
  private final List<DomainEvent> events = SocialFakes.eventSink();
  private final OpenDmUseCase useCase =
      new OpenDmUseCase(friendships, dms, refs, events::add, Clock.fixed(NOW, ZoneOffset.UTC));

  // Kịch bản: mở DM lần đầu giữa hai người bạn
  // Input: execute(me, friend) khi là bạn và chưa có DM
  // Output: created=true, kênh được lưu và ghi DM ref, đúng một DmChannelCreated
  @Test
  void firstOpenCreatesChannelRefAndEvent() {
    friendships.insertIfAbsent(pair, NOW);

    OpenDmResult result = useCase.execute(me, friend);

    assertThat(result.created()).isTrue();
    assertThat(result.dm().friendId()).isEqualTo(friend.value());
    assertThat(result.dm().createdAt()).isEqualTo(NOW);
    assertThat(dms.byPair.get(pair).id()).isEqualTo(result.dm().channelId());
    assertThat(refs.dmIds).containsExactly(result.dm().channelId());
    assertThat(events)
        .containsExactly(
            new DmChannelCreated(new ChannelId(result.dm().channelId()), pair.low(), pair.high()));
  }

  // Kịch bản: mở lại DM đã có, từ phía người kia
  // Input: me mở rồi friend mở với me
  // Output: lần hai created=false, cùng channelId, friendId là me, không thêm sự kiện
  @Test
  void secondOpenReturnsSameChannelWithoutEvent() {
    friendships.insertIfAbsent(pair, NOW);
    OpenDmResult first = useCase.execute(me, friend);

    OpenDmResult second = useCase.execute(friend, me);

    assertThat(second.created()).isFalse();
    assertThat(second.dm().channelId()).isEqualTo(first.dm().channelId());
    assertThat(second.dm().friendId()).isEqualTo(me.value());
    assertThat(events).hasSize(1);
  }

  // Kịch bản: mở DM với người không phải bạn
  // Input: execute(me, friend) khi chưa là bạn
  // Output: NotFriendsException, không lưu kênh, không sự kiện
  @Test
  void strangerIsRejected() {
    assertThatThrownBy(() -> useCase.execute(me, friend)).isInstanceOf(NotFriendsException.class);

    assertThat(dms.byPair).isEmpty();
    assertThat(events).isEmpty();
  }

  // Kịch bản: hủy kết bạn rồi mở lại DM cũ
  // Input: mở khi là bạn, xóa friendship, mở lần nữa
  // Output: NotFriendsException dù DM cũ còn, không thêm sự kiện
  @Test
  void unfriendedOldDmIsRejected() {
    friendships.insertIfAbsent(pair, NOW);
    useCase.execute(me, friend);
    friendships.delete(pair);

    assertThatThrownBy(() -> useCase.execute(me, friend)).isInstanceOf(NotFriendsException.class);

    assertThat(events).hasSize(1);
  }

  // Kịch bản: mở DM với chính mình
  // Input: execute(me, me)
  // Output: DomainValidationException
  @Test
  void selfIsRejected() {
    assertThatThrownBy(() -> useCase.execute(me, me)).isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: thua đua, request khác chèn kênh trước giữa lúc kiểm tra và chèn
  // Input: execute khi insertIfAbsent trả false vì người thắng đã chèn kênh
  // Output: created=false, channelId của người thắng, không ghi ref, không sự kiện
  @Test
  void lostRaceReturnsWinnerWithoutRefOrEvent() {
    friendships.insertIfAbsent(pair, NOW);
    DmChannel winner = DmChannel.rehydrate(UUID.randomUUID(), pair, NOW);
    dms.winnerOnInsert = winner;

    OpenDmResult result = useCase.execute(me, friend);

    assertThat(result.created()).isFalse();
    assertThat(result.dm().channelId()).isEqualTo(winner.id());
    assertThat(refs.dmIds).isEmpty();
    assertThat(events).isEmpty();
  }

  // Kịch bản: liệt kê DM sau khi hết bạn
  // Input: mở DM, xóa friendship, ListDmsQuery cho cả hai phía và một người lạ
  // Output: mỗi phía thấy DM với friendId là người kia, người lạ thấy rỗng
  @Test
  void listKeepsDmsAfterUnfriend() {
    friendships.insertIfAbsent(pair, NOW);
    OpenDmResult opened = useCase.execute(me, friend);
    friendships.delete(pair);
    ListDmsQuery query = new ListDmsQuery(dms);

    assertThat(query.execute(me))
        .containsExactly(new DmView(opened.dm().channelId(), friend.value(), NOW));
    assertThat(query.execute(friend))
        .containsExactly(new DmView(opened.dm().channelId(), me.value(), NOW));
    assertThat(query.execute(new UserId(UUID.randomUUID()))).isEmpty();
  }

  // Repository kênh DM giả, winnerOnInsert giả lập request khác thắng đua chèn
  private static final class FakeDms implements DmChannelRepository {
    final Map<UserPair, DmChannel> byPair = new LinkedHashMap<>();
    DmChannel winnerOnInsert;

    // Tìm theo cặp
    @Override
    public Optional<DmChannel> findByPair(UserPair pair) {
      return Optional.ofNullable(byPair.get(pair));
    }

    // Tìm theo id
    @Override
    public Optional<DmChannel> findById(ChannelId id) {
      return byPair.values().stream().filter(c -> c.id().equals(id.value())).findFirst();
    }

    // Chèn nếu cặp chưa có; có người thắng đua thì lưu kênh của họ và trả false
    @Override
    public boolean insertIfAbsent(DmChannel channel) {
      if (winnerOnInsert != null) {
        byPair.put(winnerOnInsert.pair(), winnerOnInsert);
        winnerOnInsert = null;
        return false;
      }
      return byPair.putIfAbsent(channel.pair(), channel) == null;
    }

    // Các kênh có user tham gia
    @Override
    public List<DmChannel> listOf(UserId user) {
      return byPair.values().stream().filter(c -> c.pair().contains(user)).toList();
    }
  }

  // Repository channel_refs giả chỉ ghi nhớ các kênh DM đã upsert
  private static final class FakeRefs implements ChannelRefRepository {
    final Set<UUID> dmIds = new LinkedHashSet<>();

    // Ghi kênh DM
    @Override
    public void upsertDmChannel(ChannelId channelId) {
      dmIds.add(channelId.value());
    }

    // Không dùng trong test này
    @Override
    public void upsertServerChannel(ChannelId channelId, UUID serverId) {
      throw new UnsupportedOperationException();
    }

    // Không dùng trong test này
    @Override
    public void tombstoneChannel(ChannelId channelId, UUID serverId, Instant at) {
      throw new UnsupportedOperationException();
    }

    // Không dùng trong test này
    @Override
    public void tombstoneServer(UUID serverId, Instant at) {
      throw new UnsupportedOperationException();
    }

    // Không dùng trong test này
    @Override
    public boolean isDeleted(ChannelId channelId) {
      throw new UnsupportedOperationException();
    }

    // Không dùng trong test này
    @Override
    public boolean isDm(ChannelId channelId) {
      throw new UnsupportedOperationException();
    }
  }
}
