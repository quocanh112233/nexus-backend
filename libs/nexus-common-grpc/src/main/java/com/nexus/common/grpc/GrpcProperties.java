package com.nexus.common.grpc;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Cấu hình app.grpc.service-callers.<tên>.{token,methods} cho xác thực service-token
@ConfigurationProperties("app.grpc")
public record GrpcProperties(Map<String, ServiceCaller> serviceCallers) {

  // Thiếu cấu hình thì coi là không có caller nào, token khác trống trùng nhau bị từ chối
  public GrpcProperties {
    serviceCallers = serviceCallers == null ? Map.of() : Map.copyOf(serviceCallers);
    Set<String> seen = new HashSet<>();
    for (ServiceCaller caller : serviceCallers.values()) {
      if (caller.token() != null && !caller.token().isBlank() && !seen.add(caller.token())) {
        throw new IllegalArgumentException("duplicate app.grpc.service-callers token");
      }
    }
  }
}
