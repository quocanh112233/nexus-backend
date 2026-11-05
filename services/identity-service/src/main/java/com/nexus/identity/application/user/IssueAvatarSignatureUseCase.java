package com.nexus.identity.application.user;

import com.nexus.identity.application.port.UploadSignature;
import com.nexus.identity.application.port.UploadSigner;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Use case cấp chữ ký upload avatar, folder cố định theo người gọi
@Service
public class IssueAvatarSignatureUseCase {

  private final UploadSigner signer;
  private final Clock clock;

  // Nhận cổng ký và đồng hồ qua constructor
  public IssueAvatarSignatureUseCase(UploadSigner signer, Clock clock) {
    this.signer = signer;
    this.clock = clock;
  }

  // Ký cho folder gốc/avatars/userId tại thời điểm hiện tại
  public UploadSignature execute(UUID userId) {
    return signer.sign(signer.folderRoot() + "/avatars/" + userId, clock.instant());
  }
}
