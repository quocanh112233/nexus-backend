package com.nexus.common.grpc;

import java.util.List;

// Cấu hình một service gọi nội bộ: token riêng và các full method name được phép gọi
public record ServiceCaller(String token, List<String> methods) {

  // Thiếu methods thì coi là rỗng
  public ServiceCaller {
    methods = methods == null ? List.of() : List.copyOf(methods);
  }
}
