package com.nexus.messaging.infrastructure.persistence.message;

import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.MessageRepository;
import com.nexus.messaging.domain.message.Nonce;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

// Cài đặt MessageRepository bằng MessageDao và MessageMapper
@Repository
class MessageRepositoryImpl implements MessageRepository {

  private final MessageDao dao;
  private final MessageMapper mapper;

  // Nhận DAO và mapper qua constructor
  MessageRepositoryImpl(MessageDao dao, MessageMapper mapper) {
    this.dao = dao;
    this.mapper = mapper;
  }

  // Lưu và flush ngay để ràng buộc nonce nổ tại đây, tin mới thì persist (version null)
  @Override
  public Message save(Message message) {
    MessageJpaEntity entity = mapper.toJpa(message);
    if (!dao.existsById(entity.id)) {
      entity.version = null;
    }
    return mapper.toDomain(dao.saveAndFlush(entity), entity.mentions);
  }

  // Tìm tin theo id
  @Override
  public Optional<Message> findById(MessageId id) {
    return dao.findById(id.value()).map(mapper::toDomain);
  }

  // Tìm tin theo tác giả và nonce
  @Override
  public Optional<Message> findByAuthorAndNonce(UserId authorId, Nonce nonce) {
    return dao.findByAuthorIdAndNonce(authorId.value(), nonce.value()).map(mapper::toDomain);
  }

  // Đọc trang keyset, mention và attachment của cả trang nạp bằng @BatchSize (IN một lần mỗi loại)
  @Override
  public List<Message> findPage(ChannelId channelId, MessageId before, MessageId after, int limit) {
    UUID channel = channelId.value();
    List<MessageJpaEntity> rows =
        after != null
            ? dao.after(channel, after.value(), limit)
            : before != null
                ? dao.before(channel, before.value(), limit)
                : dao.latest(channel, limit);
    return rows.stream().map(mapper::toDomain).toList();
  }
}
