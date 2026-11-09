package com.nexus.presence.domain.relation;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.presence.application.relation.FakeRelationshipProjection;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra quy tắc ai được thấy trạng thái của ai trên projection quan hệ trong bộ nhớ
class PresenceAudiencePolicyTest {

  private final FakeRelationshipProjection projection = new FakeRelationshipProjection();
  private final UUID viewer = UUID.randomUUID();
  private final UUID target = UUID.randomUUID();

  // Kịch bản: user xem chính mình dù chưa nạp quan hệ
  // Input: viewer trùng target, projection rỗng
  // Output: canSee trả true
  @Test
  void selfIsAlwaysVisible() {
    assertThat(PresenceAudiencePolicy.canSee(viewer, viewer, projection)).isTrue();
  }

  // Kịch bản: hai user chung đúng một server
  // Input: viewer {s1}, target {s1, s2}
  // Output: canSee trả true
  @Test
  void sharingOneServerIsVisible() {
    UUID s1 = UUID.randomUUID();
    projection.load(viewer, Set.of(s1));
    projection.load(target, Set.of(s1, UUID.randomUUID()));

    assertThat(PresenceAudiencePolicy.canSee(viewer, target, projection)).isTrue();
  }

  // Kịch bản: hai user chung nhiều server
  // Input: viewer {s1, s2}, target {s1, s2}
  // Output: canSee trả true
  @Test
  void sharingManyServersIsVisible() {
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    projection.load(viewer, Set.of(s1, s2));
    projection.load(target, Set.of(s1, s2));

    assertThat(PresenceAudiencePolicy.canSee(viewer, target, projection)).isTrue();
  }

  // Kịch bản: hai user không chung server nào
  // Input: viewer {s1}, target {s2}
  // Output: canSee trả false
  @Test
  void strangerIsNotVisible() {
    projection.load(viewer, Set.of(UUID.randomUUID()));
    projection.load(target, Set.of(UUID.randomUUID()));

    assertThat(PresenceAudiencePolicy.canSee(viewer, target, projection)).isFalse();
  }

  // Kịch bản: viewer chưa nạp quan hệ nên không chứng minh được chung server
  // Input: viewer chưa loaded, target {s1}
  // Output: canSee trả false (fail-closed)
  @Test
  void unloadedViewerSeesNobodyElse() {
    projection.load(target, Set.of(UUID.randomUUID()));

    assertThat(PresenceAudiencePolicy.canSee(viewer, target, projection)).isFalse();
  }
}
