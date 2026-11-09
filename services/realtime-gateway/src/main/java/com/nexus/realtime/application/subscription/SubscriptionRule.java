package com.nexus.realtime.application.subscription;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Đổi destination STOMP thành đích đã biết, destination lạ bị từ chối
public final class SubscriptionRule {

  // Loại đích STOMP mà gateway chấp nhận
  public enum Kind {
    CHANNEL,
    SERVER,
    USER_EVENTS,
    TYPING_SEND
  }

  // Đích đã phân tích, id là null với USER_EVENTS
  public record Target(Kind kind, UUID id) {}

  private static final String UUID_RE =
      "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";
  private static final Pattern CHANNEL = Pattern.compile("/topic/channels/" + UUID_RE);
  private static final Pattern SERVER = Pattern.compile("/topic/servers/" + UUID_RE);
  private static final Pattern TYPING = Pattern.compile("/app/channels/" + UUID_RE + "/typing");

  // Không cho tạo instance
  private SubscriptionRule() {}

  // Phân tích destination, ném IllegalArgumentException nếu không thuộc bốn dạng cho phép
  public static Target parse(String destination) {
    if ("/user/queue/events".equals(destination)) {
      return new Target(Kind.USER_EVENTS, null);
    }
    Target target = match(CHANNEL, Kind.CHANNEL, destination);
    target = target != null ? target : match(SERVER, Kind.SERVER, destination);
    target = target != null ? target : match(TYPING, Kind.TYPING_SEND, destination);
    if (target == null) {
      throw new IllegalArgumentException("unsupported destination");
    }
    return target;
  }

  // Trả Target nếu destination khớp mẫu với uuid chuẩn chữ thường, null nếu không
  private static Target match(Pattern pattern, Kind kind, String destination) {
    if (destination == null) {
      return null;
    }
    Matcher matcher = pattern.matcher(destination);
    return matcher.matches() ? new Target(kind, UUID.fromString(matcher.group(1))) : null;
  }
}
