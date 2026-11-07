package com.nexus.messaging.application.message;

import com.nexus.messaging.application.attachment.AttachmentInput;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.Nonce;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import java.util.List;

// Yêu cầu gửi tin; nonce và replyToId có thể null
public record SendMessageCommand(
    ChannelId channelId,
    UserId authorId,
    String content,
    Nonce nonce,
    MessageId replyToId,
    List<AttachmentInput> attachments) {}
