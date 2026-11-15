package com.nexus.messaging.infrastructure.persistence.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.ChannelPermission;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.DmChannel;
import com.nexus.messaging.domain.social.DmChannelRepository;
import com.nexus.messaging.domain.social.FriendshipRepository;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra DmAwareChannelPermissionChecker: DM tự quyết theo bạn bè, kênh server hỏi delegate
class DmAwareChannelPermissionCheckerTest {

  private static final ChannelPermissionChecker FORBIDDEN_DELEGATE =
      (u, c) -> {
        throw new AssertionError("delegate must not be called for DM");
      };

  private final UserId alice = new UserId(UUID.randomUUID());
  private final UserId bob = new UserId(UUID.randomUUID());
  private final UserId stranger = new UserId(UUID.randomUUID());
  private final UserPair pair = UserPair.of(alice, bob);
  private final ChannelId dm = new ChannelId(UUID.randomUUID());
  private final FakeRefs refs = new FakeRefs();
  private final FakeDms dms = new FakeDms();
  private final FakeFriendships friendships = new FakeFriendships();

  // Dựng checker quanh các fake và delegate cho trước
  private ChannelPermissionChecker checker(ChannelPermissionChecker delegate) {
    return new DmAwareChannelPermissionChecker(refs, dms, friendships, delegate);
  }

  // Ghi sẵn kênh DM giữa alice và bob
  private void seedDm() {
    refs.dm.add(dm);
    dms.channel = DmChannel.rehydrate(dm.value(), pair, Instant.EPOCH);
  }

  // Kịch bản: kênh server không phải DM
  // Input: channel_refs không đánh dấu DM, delegate trả ChannelAccess(server, {VIEW_CHANNEL})
  // Output: trả nguyên kết quả delegate
  @Test
  void serverChannelGoesToDelegate() {
    UUID server = UUID.randomUUID();
    ChannelAccess fromDelegate = new ChannelAccess(server, Set.of(ChannelPermission.VIEW_CHANNEL));

    ChannelAccess access = checker((u, c) -> fromDelegate).check(alice, dm);

    assertThat(access).isEqualTo(fromDelegate);
    assertThat(access.isDm()).isFalse();
  }

  // Kịch bản: hai người tham gia đang là bạn, delegate ném lỗi nếu bị gọi
  // Input: check(alice, dm) và check(bob, dm), friendship tồn tại
  // Output: serverId null, isDm true, đủ 5 quyền DM, delegate không bị gọi
  @Test
  void friendsGetFullPermissionsWithoutDelegate() {
    seedDm();
    friendships.pairs.add(pair);

    for (UserId user : List.of(alice, bob)) {
      ChannelAccess access = checker(FORBIDDEN_DELEGATE).check(user, dm);

      assertThat(access.serverId()).isNull();
      assertThat(access.isDm()).isTrue();
      assertThat(access.granted())
          .containsExactlyInAnyOrder(
              ChannelPermission.VIEW_CHANNEL,
              ChannelPermission.SEND_MESSAGES,
              ChannelPermission.READ_MESSAGE_HISTORY,
              ChannelPermission.ATTACH_FILES,
              ChannelPermission.ADD_REACTIONS);
    }
  }

  // Kịch bản: người tham gia đã hủy kết bạn
  // Input: check(alice, dm), không có friendship, delegate ném lỗi nếu bị gọi
  // Output: chỉ VIEW_CHANNEL và READ_MESSAGE_HISTORY
  @Test
  void exFriendsCanOnlyRead() {
    seedDm();

    ChannelAccess access = checker(FORBIDDEN_DELEGATE).check(alice, dm);

    assertThat(access.granted())
        .containsExactlyInAnyOrder(
            ChannelPermission.VIEW_CHANNEL, ChannelPermission.READ_MESSAGE_HISTORY);
  }

  // Kịch bản: người ngoài hỏi quyền kênh DM, kể cả khi alice và bob là bạn
  // Input: check(stranger, dm), delegate ném lỗi nếu bị gọi
  // Output: ChannelNotFoundException, không lộ sự tồn tại của kênh
  @Test
  void strangerGetsNotFound() {
    seedDm();
    friendships.pairs.add(pair);

    assertThatThrownBy(() -> checker(FORBIDDEN_DELEGATE).check(stranger, dm))
        .isInstanceOf(ChannelNotFoundException.class);
  }

  // Kịch bản: channel_refs đánh dấu DM nhưng thiếu dòng dm_channels
  // Input: refs.dm chứa kênh, kho DM rỗng
  // Output: ChannelNotFoundException, delegate không bị gọi
  @Test
  void dmRefWithoutDmRowIsNotFound() {
    refs.dm.add(dm);

    assertThatThrownBy(() -> checker(FORBIDDEN_DELEGATE).check(alice, dm))
        .isInstanceOf(ChannelNotFoundException.class);
  }

  // Kịch bản: kênh DM đã bị tombstone
  // Input: seedDm, refs.deleted chứa kênh, đang là bạn
  // Output: ChannelNotFoundException, delegate không bị gọi
  @Test
  void tombstonedDmIsNotFound() {
    seedDm();
    friendships.pairs.add(pair);
    refs.deleted.add(dm);

    assertThatThrownBy(() -> checker(FORBIDDEN_DELEGATE).check(alice, dm))
        .isInstanceOf(ChannelNotFoundException.class);
  }

  // Kho tham chiếu kênh giả chỉ nhớ tập kênh DM và tập kênh đã xóa
  private static final class FakeRefs implements ChannelRefRepository {
    final Set<ChannelId> dm = new HashSet<>();
    final Set<ChannelId> deleted = new HashSet<>();

    // Không dùng trong test này
    @Override
    public void upsertServerChannel(ChannelId channelId, UUID serverId) {}

    // Ghi kênh vào tập DM
    @Override
    public void upsertDmChannel(ChannelId channelId) {
      dm.add(channelId);
    }

    // Không dùng trong test này
    @Override
    public void tombstoneChannel(ChannelId channelId, UUID serverId, Instant at) {}

    // Không dùng trong test này
    @Override
    public void tombstoneServer(UUID serverId, Instant at) {}

    // Kênh có trong tập đã xóa không
    @Override
    public boolean isDeleted(ChannelId channelId) {
      return deleted.contains(channelId);
    }

    // Kênh có trong tập DM không
    @Override
    public boolean isDm(ChannelId channelId) {
      return dm.contains(channelId);
    }
  }

  // Kho DM giả giữ tối đa một kênh
  private static final class FakeDms implements DmChannelRepository {
    DmChannel channel;

    // Không dùng trong test này
    @Override
    public Optional<DmChannel> findByPair(UserPair pair) {
      return Optional.empty();
    }

    // Trả kênh nếu trùng id
    @Override
    public Optional<DmChannel> findById(ChannelId id) {
      return Optional.ofNullable(channel).filter(c -> c.id().equals(id.value()));
    }

    // Không dùng trong test này
    @Override
    public boolean insertIfAbsent(DmChannel c) {
      return false;
    }

    // Không dùng trong test này
    @Override
    public List<DmChannel> listOf(UserId user) {
      return List.of();
    }

    // Không dùng trong test này
    @Override
    public List<DmChannel> pageAll(UUID afterId, int limit) {
      return List.of();
    }
  }

  // Kho bạn bè giả là một tập cặp
  private static final class FakeFriendships implements FriendshipRepository {
    final Set<UserPair> pairs = new HashSet<>();

    // Không dùng trong test này
    @Override
    public boolean insertIfAbsent(UserPair p, Instant createdAt) {
      return false;
    }

    // Không dùng trong test này
    @Override
    public boolean delete(UserPair p) {
      return false;
    }

    // Cặp có trong tập không
    @Override
    public boolean exists(UserPair p) {
      return pairs.contains(p);
    }

    // Không dùng trong test này
    @Override
    public List<UserId> friendIdsOf(UserId user) {
      return List.of();
    }
  }
}
