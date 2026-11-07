package com.nexus.messaging.application.attachment;

import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.port.UploadSignature;
import com.nexus.messaging.application.port.UploadSigner;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Clock;
import org.springframework.stereotype.Service;

// Use case cấp chữ ký upload vào thư mục riêng của user trong kênh, cần ATTACH_FILES
@Service
public class CreateAttachmentSignatureUseCase {

  private final ChannelPermissionChecker checker;
  private final UploadSigner signer;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, cổng ký và đồng hồ qua constructor
  public CreateAttachmentSignatureUseCase(
      ChannelPermissionChecker checker, UploadSigner signer, Clock clock) {
    this.checker = checker;
    this.signer = signer;
    this.clock = clock;
  }

  // Kiểm quyền ATTACH_FILES rồi ký folder {root}/attachments/{channelId}/{userId}
  public UploadSignature execute(ChannelId channelId, UserId userId) {
    checker.check(userId, channelId).require(ChannelPermission.ATTACH_FILES);
    return signer.sign(
        AttachmentInput.folder(signer.folderRoot(), channelId, userId), clock.instant());
  }
}
