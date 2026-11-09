package com.nexus.realtime;

import com.nexus.common.grpc.GrpcAuth;
import com.nexus.proto.community.v1.CheckServerMembershipRequest;
import com.nexus.proto.community.v1.CheckServerMembershipResponse;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import com.nexus.proto.messaging.v1.ChannelAccessServiceGrpc;
import com.nexus.proto.messaging.v1.CheckChannelAccessRequest;
import com.nexus.proto.messaging.v1.CheckChannelAccessResponse;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

// Máy chủ gRPC Netty cổng ngẫu nhiên giả Messaging và Community, đáp theo user, kênh, server
public final class FakeAccessServer implements AutoCloseable {

  private final Map<String, UUID> views = new ConcurrentHashMap<>();
  private final Set<String> members = ConcurrentHashMap.newKeySet();
  private final AtomicReference<String> lastToken = new AtomicReference<>();
  private volatile boolean failing;
  private volatile long delayMillis;
  private final Server server;

  // Khởi động server với cả ChannelAccessService và PermissionService, ghi lại service token
  public FakeAccessServer() {
    ServerInterceptor capture =
        new ServerInterceptor() {
          // Ghi x-service-token của call rồi chuyển tiếp
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            lastToken.set(headers.get(GrpcAuth.SERVICE_TOKEN));
            return next.startCall(call, headers);
          }
        };
    try {
      server =
          NettyServerBuilder.forPort(0)
              .addService(ServerInterceptors.intercept(new Channels(), capture))
              .addService(ServerInterceptors.intercept(new Members(), capture))
              .build()
              .start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // Địa chỉ gRPC để đặt vào app.grpc.*.target
  public String target() {
    return "localhost:" + server.getPort();
  }

  // Cho user xem được kênh, server của kênh suy ra từ id kênh
  public void allowView(UUID userId, UUID channelId) {
    allowView(userId, channelId, UUID.nameUUIDFromBytes(channelId.toString().getBytes()));
  }

  // Cho user xem được kênh thuộc server cho trước
  public void allowView(UUID userId, UUID channelId, UUID serverId) {
    views.put(userId + ":" + channelId, serverId);
  }

  // Cho user là member của server
  public void allowMember(UUID userId, UUID serverId) {
    members.add(userId + ":" + serverId);
  }

  // Thu lại quyền xem kênh của user
  public void denyView(UUID userId, UUID channelId) {
    views.remove(userId + ":" + channelId);
  }

  // Thu lại tư cách member server của user
  public void denyMember(UUID userId, UUID serverId) {
    members.remove(userId + ":" + serverId);
  }

  // Bật hoặc tắt chế độ mọi call trả UNAVAILABLE
  public void failing(boolean failing) {
    this.failing = failing;
  }

  // Đặt độ trễ mỗi call để mô phỏng server chậm
  public void delay(long millis) {
    this.delayMillis = millis;
  }

  // Service token của call gần nhất, null nếu chưa có
  public String lastToken() {
    return lastToken.get();
  }

  // Xóa mọi cấu hình về trạng thái ban đầu
  public void reset() {
    views.clear();
    members.clear();
    failing = false;
    delayMillis = 0;
    lastToken.set(null);
  }

  // Dừng server
  @Override
  public void close() {
    server.shutdownNow();
  }

  // Chờ độ trễ cấu hình, trả true nếu phải báo lỗi thay vì trả lời
  private boolean failOrSlow(StreamObserver<?> observer) {
    try {
      Thread.sleep(delayMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (failing) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
    return failing;
  }

  // Giả ChannelAccessService của Messaging
  private final class Channels extends ChannelAccessServiceGrpc.ChannelAccessServiceImplBase {

    // Trả can_view theo cấu hình allowView
    @Override
    public void checkChannelAccess(
        CheckChannelAccessRequest request, StreamObserver<CheckChannelAccessResponse> observer) {
      if (failOrSlow(observer)) {
        return;
      }
      UUID serverId = views.get(request.getUserId() + ":" + request.getChannelId());
      CheckChannelAccessResponse.Builder response =
          CheckChannelAccessResponse.newBuilder().setCanView(serverId != null);
      if (serverId != null) {
        response.setServerId(serverId.toString());
      }
      observer.onNext(response.build());
      observer.onCompleted();
    }
  }

  // Giả PermissionService của Community
  private final class Members extends PermissionServiceGrpc.PermissionServiceImplBase {

    // Trả is_member theo cấu hình allowMember
    @Override
    public void checkServerMembership(
        CheckServerMembershipRequest request,
        StreamObserver<CheckServerMembershipResponse> observer) {
      if (failOrSlow(observer)) {
        return;
      }
      boolean member = members.contains(request.getUserId() + ":" + request.getServerId());
      observer.onNext(CheckServerMembershipResponse.newBuilder().setIsMember(member).build());
      observer.onCompleted();
    }
  }
}
