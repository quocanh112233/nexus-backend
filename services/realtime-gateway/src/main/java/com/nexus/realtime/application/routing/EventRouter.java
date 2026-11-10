package com.nexus.realtime.application.routing;

import com.nexus.common.messaging.EventEnvelope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Bảng định tuyến event sang destination STOMP theo realtime-design, event lạ thì không có route
public final class EventRouter {

  private static final String USER_QUEUE = "/queue/events";
  private static final Set<String> CHANNEL_EVENTS =
      Set.of(
          "MessageCreated",
          "MessageEdited",
          "MessageDeleted",
          "ReactionAdded",
          "ReactionRemoved",
          "TypingStarted");
  private static final Set<String> SERVER_EVENTS =
      Set.of(
          "ServerUpdated",
          "ServerDeleted",
          "CategoryCreated",
          "CategoryUpdated",
          "CategoryDeleted",
          "ChannelCreated",
          "ChannelUpdated",
          "ChannelDeleted",
          "ChannelPermissionsChanged",
          "RoleCreated",
          "RoleUpdated",
          "RoleDeleted",
          "MemberJoined",
          "MemberLeft",
          "MemberKicked",
          "MemberBanned",
          "MemberUnbanned",
          "MemberRolesChanged");
  private static final Set<String> USER_TOO =
      Set.of("MemberJoined", "MemberKicked", "MemberBanned");

  // Không cho tạo instance
  private EventRouter() {}

  // Tính route của event, thiếu hoặc sai id trong payload thì ném IllegalArgumentException
  public static List<Route> route(EventEnvelope envelope) {
    String type = envelope.eventType();
    Map<String, Object> payload = envelope.payload();
    List<Route> routes = new ArrayList<>();
    if (CHANNEL_EVENTS.contains(type)) {
      routes.add(topic("channels", id(payload, "channelId"), null));
    }
    if (SERVER_EVENTS.contains(type)) {
      routes.add(
          topic(
              "servers",
              id(payload, "serverId"),
              switch (type) {
                case "MemberBanned" -> pick(payload, "serverId", "userId", "wasMember");
                case "MemberKicked" -> pick(payload, "serverId", "userId");
                default -> null;
              }));
    }
    if (USER_TOO.contains(type)) {
      routes.add(user(id(payload, "userId")));
    }
    if ("ServerCreated".equals(type)) {
      routes.add(user(id(payload, "ownerId")));
    }
    if ("PresenceChanged".equals(type)) {
      Map<String, Object> data = pick(payload, "userId", "status", "changedAt");
      ids(payload, "serverIds").forEach(id -> routes.add(topic("servers", id, data)));
      ids(payload, "friendIds").forEach(id -> routes.add(new Route(USER_QUEUE, id, data)));
    }
    social(type, payload, routes);
    return routes;
  }

  // Event bạn bè và DM giao vào /queue/events của người liên quan, data chỉ giữ các id
  private static void social(String type, Map<String, Object> payload, List<Route> routes) {
    switch (type) {
      case "FriendRequestSent", "FriendRequestCanceled" ->
          to(routes, request(payload), id(payload, "addresseeId"));
      case "FriendRequestAccepted" ->
          to(routes, request(payload), id(payload, "requesterId"), id(payload, "addresseeId"));
      case "FriendRequestDeclined" -> to(routes, request(payload), id(payload, "requesterId"));
      case "FriendRemoved" ->
          to(
              routes,
              pick(payload, "userId", "friendId"),
              id(payload, "userId"),
              id(payload, "friendId"));
      case "DmChannelCreated" ->
          to(
              routes,
              pick(payload, "channelId", "participantIds"),
              ids(payload, "participantIds").toArray(UUID[]::new));
      default -> {}
    }
  }

  // Phần data của event lời mời kết bạn: chỉ các id
  private static Map<String, Object> request(Map<String, Object> payload) {
    return pick(payload, "requestId", "requesterId", "addresseeId");
  }

  // Thêm route /queue/events với cùng data cho từng người nhận
  private static void to(List<Route> routes, Map<String, Object> data, UUID... recipients) {
    for (UUID recipient : recipients) {
      routes.add(new Route(USER_QUEUE, recipient, data));
    }
  }

  // Route tới topic của kênh hoặc server, data null thì giữ nguyên payload
  private static Route topic(String kind, UUID id, Map<String, Object> data) {
    return new Route("/topic/" + kind + "/" + id, null, data);
  }

  // Giữ lại các khóa có trong payload, bỏ phần còn lại (danh sách người nhận, lý do ban, kickedBy)
  private static Map<String, Object> pick(Map<String, Object> payload, String... keys) {
    Map<String, Object> data = new LinkedHashMap<>();
    for (String key : keys) {
      if (payload.containsKey(key)) {
        data.put(key, payload.get(key));
      }
    }
    return data;
  }

  // Route tới /user/queue/events của user
  private static Route user(UUID userId) {
    return new Route(USER_QUEUE, userId, null);
  }

  // Đọc một id UUID bắt buộc từ payload
  private static UUID id(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Payload thiếu " + key);
    }
    return UUID.fromString(value.toString());
  }

  // Đọc danh sách id UUID từ payload, thiếu thì coi như rỗng
  private static List<UUID> ids(Map<String, Object> payload, String key) {
    return payload.get(key) instanceof List<?> list
        ? list.stream().map(v -> UUID.fromString(v.toString())).toList()
        : List.of();
  }
}
