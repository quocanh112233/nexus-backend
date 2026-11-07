package com.nexus.common.grpc;

import io.grpc.BindableService;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Server gRPC in-process dùng chung cho test, gắn sẵn interceptor và kênh client tương ứng
final class GrpcTestServer implements AutoCloseable {

  private final Server server;
  private final ManagedChannel channel;

  // Khởi động server in-process kèm kênh client, interceptor server và client có thể null
  GrpcTestServer(
      ServerInterceptor serverInterceptor,
      ClientInterceptor clientInterceptor,
      BindableService... services) {
    String name = UUID.randomUUID().toString();
    InProcessServerBuilder builder = InProcessServerBuilder.forName(name).directExecutor();
    for (BindableService service : services) {
      builder.addService(service);
    }
    if (serverInterceptor != null) {
      builder.intercept(serverInterceptor);
    }
    try {
      server = builder.build().start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    InProcessChannelBuilder channelBuilder = InProcessChannelBuilder.forName(name).directExecutor();
    if (clientInterceptor != null) {
      channelBuilder.intercept(clientInterceptor);
    }
    channel = channelBuilder.build();
  }

  // Kênh client nối tới server
  ManagedChannel channel() {
    return channel;
  }

  // Đóng kênh và server
  @Override
  public void close() {
    channel.shutdownNow();
    server.shutdownNow();
    try {
      server.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
