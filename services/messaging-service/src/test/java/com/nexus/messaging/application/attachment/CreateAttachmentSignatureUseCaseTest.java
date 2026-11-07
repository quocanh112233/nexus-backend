package com.nexus.messaging.application.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.port.UploadSignature;
import com.nexus.messaging.application.port.UploadSigner;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.application.shared.PermissionDeniedException;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra CreateAttachmentSignatureUseCase bằng checker và signer giả
class CreateAttachmentSignatureUseCaseTest {

  private final ChannelId channel = new ChannelId(UUID.randomUUID());
  private final UserId user = new UserId(UUID.randomUUID());
  private ChannelAccess access =
      new ChannelAccess(
          UUID.randomUUID(),
          EnumSet.of(ChannelPermission.VIEW_CHANNEL, ChannelPermission.ATTACH_FILES));
  private final ChannelPermissionChecker checker = (u, c) -> access;
  private final CreateAttachmentSignatureUseCase useCase =
      new CreateAttachmentSignatureUseCase(
          checker,
          new UploadSigner() {
            // Ký giả trả lại folder và timestamp nhận được
            @Override
            public UploadSignature sign(String folder, Instant now) {
              return new UploadSignature("demo", "key", now.getEpochSecond(), "sig", folder, "p");
            }

            // Thư mục gốc cố định cho test
            @Override
            public String folderRoot() {
              return "nexus-dev";
            }
          },
          Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC));

  // Kịch bản: user thấy kênh nhưng thiếu ATTACH_FILES
  // Input: quyền chỉ có VIEW_CHANNEL
  // Output: PermissionDeniedException
  @Test
  void needsAttachFiles() {
    access = new ChannelAccess(UUID.randomUUID(), EnumSet.of(ChannelPermission.VIEW_CHANNEL));

    assertThatThrownBy(() -> useCase.execute(channel, user))
        .isInstanceOf(PermissionDeniedException.class);
  }

  // Kịch bản: có ATTACH_FILES
  // Input: execute với kênh và user cho trước
  // Output: folder là nexus-dev/attachments/{channelId}/{userId}, timestamp theo đồng hồ
  @Test
  void folderContainsChannelAndUser() {
    UploadSignature sig = useCase.execute(channel, user);

    assertThat(sig.folder())
        .isEqualTo("nexus-dev/attachments/" + channel.value() + "/" + user.value());
    assertThat(sig.timestamp()).isEqualTo(Instant.parse("2026-11-07T10:00:00Z").getEpochSecond());
  }

  // Kịch bản: user không thấy kênh
  // Input: tập quyền rỗng
  // Output: ChannelNotFoundException
  @Test
  void hiddenChannelIs404() {
    access = new ChannelAccess(UUID.randomUUID(), EnumSet.noneOf(ChannelPermission.class));

    assertThatThrownBy(() -> useCase.execute(channel, user))
        .isInstanceOf(ChannelNotFoundException.class);
  }
}
