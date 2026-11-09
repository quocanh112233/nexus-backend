package com.nexus.presence.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;

// Kiểm tra kênh gRPC của presence-service kết nối ngay thay vì chờ call đầu tiên
class GrpcClientsConfigTest {

  // Kịch bản: kênh dựng xong là rời trạng thái IDLE, call đầu không phải trả giá kết nối
  // Input: communityChannel("localhost:1", "tok") rồi chờ tối đa 2 giây
  // Output: trạng thái kênh khác IDLE
  @Test
  void channelLeavesIdleWithoutAnyCall() throws Exception {
    ManagedChannel channel = new GrpcClientsConfig().communityChannel("localhost:1", "tok");
    try {
      long deadline = System.nanoTime() + 2_000_000_000L;
      while (channel.getState(false) == ConnectivityState.IDLE && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }

      assertThat(channel.getState(false)).isNotEqualTo(ConnectivityState.IDLE);
    } finally {
      channel.shutdownNow();
    }
  }
}
