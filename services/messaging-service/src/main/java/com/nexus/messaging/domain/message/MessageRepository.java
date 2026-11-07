package com.nexus.messaging.domain.message;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import java.util.List;
import java.util.Optional;

// Cổng lưu trữ aggregate Message
public interface MessageRepository {

  // Lưu tin (kèm mention, attachment) và trả bản đã lưu
  Message save(Message message);

  // Tìm tin theo id
  Optional<Message> findById(MessageId id);

  // Tìm tin theo tác giả và nonce để gửi lại idempotent
  Optional<Message> findByAuthorAndNonce(UserId authorId, Nonce nonce);

  // Trang keyset của kênh: before hoặc không con trỏ thì id giảm dần, after thì id tăng dần
  List<Message> findPage(ChannelId channelId, MessageId before, MessageId after, int limit);
}
