package com.nexus.messaging.application.social;

import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendRequest;
import com.nexus.messaging.domain.social.FriendRequestExistsException;
import com.nexus.messaging.domain.social.FriendRequestId;
import com.nexus.messaging.domain.social.FriendRequestRepository;
import com.nexus.messaging.domain.social.FriendRequestStatus;
import com.nexus.messaging.domain.social.FriendshipRepository;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

// Bộ fake in-memory dùng chung cho test use case bạn bè: repository, transaction và event
final class SocialFakes {

  // Lớp tiện ích, không khởi tạo
  private SocialFakes() {}

  // Transaction manager giả chỉ đếm số lần commit và rollback
  static final class FakeTx implements PlatformTransactionManager {
    int commits;
    int rollbacks;

    // Template chạy qua manager giả này
    TransactionTemplate template() {
      return new TransactionTemplate(this);
    }

    // Mở transaction giả
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    // Đếm một lần commit
    @Override
    public void commit(TransactionStatus status) {
      commits++;
    }

    // Đếm một lần rollback
    @Override
    public void rollback(TransactionStatus status) {
      rollbacks++;
    }
  }

  // Repository lời mời giả mô phỏng index unique một phần theo cặp, có thể giả lập race
  static final class FakeRequests implements FriendRequestRepository {
    final Map<FriendRequestId, FriendRequest> byId = new LinkedHashMap<>();
    FriendRequest racedOnNextSave;
    RuntimeException failNextSave;

    // Thêm sẵn một lời mời vào kho dưới dạng bản đã lưu (không còn sự kiện chờ phát)
    FriendRequest put(FriendRequest request) {
      FriendRequest stored = copyOf(request, request.version());
      byId.put(stored.id(), stored);
      return stored;
    }

    // Dựng lại bản sao như repository thật rehydrate từ DB
    private static FriendRequest copyOf(FriendRequest request, long version) {
      return FriendRequest.rehydrate(
          request.id(),
          request.requester(),
          request.addressee(),
          request.status(),
          version,
          request.createdAt(),
          request.respondedAt());
    }

    // Lưu bản mới version+1; PENDING trùng cặp hoặc race giả lập thì ném FriendRequestExists
    @Override
    public FriendRequest save(FriendRequest request) {
      if (failNextSave != null) {
        RuntimeException e = failNextSave;
        failNextSave = null;
        throw e;
      }
      if (racedOnNextSave != null) {
        put(racedOnNextSave);
        racedOnNextSave = null;
        throw new FriendRequestExistsException("raced");
      }
      UserPair pair = UserPair.of(request.requester(), request.addressee());
      Optional<FriendRequest> other = findPending(pair);
      if (request.status() == FriendRequestStatus.PENDING
          && other.isPresent()
          && !other.get().id().equals(request.id())) {
        throw new FriendRequestExistsException("pending exists");
      }
      FriendRequest saved = copyOf(request, request.version() + 1);
      byId.put(saved.id(), saved);
      return saved;
    }

    // Tìm theo id
    @Override
    public Optional<FriendRequest> findById(FriendRequestId id) {
      return Optional.ofNullable(byId.get(id));
    }

    // Tìm lời mời PENDING của cặp
    @Override
    public Optional<FriendRequest> findPending(UserPair pair) {
      return byId.values().stream()
          .filter(r -> r.status() == FriendRequestStatus.PENDING)
          .filter(r -> pair.contains(r.requester()) && pair.contains(r.addressee()))
          .findFirst();
    }

    // Lời mời PENDING gửi tới user
    @Override
    public List<FriendRequest> pendingIncoming(UserId user) {
      return pending().filter(r -> r.addressee().equals(user)).toList();
    }

    // Lời mời PENDING do user gửi
    @Override
    public List<FriendRequest> pendingOutgoing(UserId user) {
      return pending().filter(r -> r.requester().equals(user)).toList();
    }

    // Các lời mời PENDING mới nhất trước
    private Stream<FriendRequest> pending() {
      return byId.values().stream()
          .filter(r -> r.status() == FriendRequestStatus.PENDING)
          .sorted(Comparator.comparing(FriendRequest::createdAt).reversed());
    }
  }

  // Repository bạn bè giả giữ tập cặp, đếm số lần chèn thật
  static final class FakeFriendships implements FriendshipRepository {
    final Map<UserPair, Instant> pairs = new LinkedHashMap<>();

    // Chèn nếu chưa có
    @Override
    public boolean insertIfAbsent(UserPair pair, Instant createdAt) {
      return pairs.putIfAbsent(pair, createdAt) == null;
    }

    // Xóa cặp
    @Override
    public boolean delete(UserPair pair) {
      return pairs.remove(pair) != null;
    }

    // Cặp có đang là bạn không
    @Override
    public boolean exists(UserPair pair) {
      return pairs.containsKey(pair);
    }

    // Id các bạn của user
    @Override
    public List<UserId> friendIdsOf(UserId user) {
      List<UserId> ids = new ArrayList<>();
      pairs.keySet().stream().filter(p -> p.contains(user)).forEach(p -> ids.add(p.other(user)));
      return ids;
    }
  }

  // Bộ gom sự kiện đã phát
  static List<DomainEvent> eventSink() {
    return new ArrayList<>();
  }
}
