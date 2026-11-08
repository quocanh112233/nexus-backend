package com.nexus.common.messaging;

// Cung cấp traceparent W3C của span hiện tại, null khi không có span
@FunctionalInterface
public interface TraceparentSupplier {

  // Trả 00-{traceId}-{spanId}-{01|00} hoặc null
  String current();
}
