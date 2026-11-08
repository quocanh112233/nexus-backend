package com.nexus.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

// Kiểm tra TraceparentSupplier dựng từ Tracer của Micrometer
class TracerTraceparentTest {

  // Dựng supplier thật từ provider giả trả về tracer cho trước
  @SuppressWarnings("unchecked")
  private static TraceparentSupplier supplier(Tracer tracer) {
    ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(tracer);
    return new OutboxAutoConfiguration.TracingConfiguration().tracerTraceparent(provider);
  }

  // Tracer giả có span t1/s1 với cờ sampled cho trước
  private static Tracer tracerWith(Boolean sampled) {
    TraceContext ctx = mock(TraceContext.class);
    when(ctx.traceId()).thenReturn("t1");
    when(ctx.spanId()).thenReturn("s1");
    when(ctx.sampled()).thenReturn(sampled);
    Span span = mock(Span.class);
    when(span.context()).thenReturn(ctx);
    Tracer tracer = mock(Tracer.class);
    when(tracer.currentSpan()).thenReturn(span);
    return tracer;
  }

  // Kịch bản: span hiện tại được sample
  // Input: Tracer có span traceId t1, spanId s1, sampled=true
  // Output: 00-t1-s1-01
  @Test
  void sampledSpanGivesFlag01() {
    assertThat(supplier(tracerWith(true)).current()).isEqualTo("00-t1-s1-01");
  }

  // Kịch bản: span hiện tại không được sample hoặc chưa quyết định
  // Input: Tracer có span với sampled=false rồi sampled=null
  // Output: cả hai trả 00-t1-s1-00
  @Test
  void unsampledOrUnknownSpanGivesFlag00() {
    assertThat(supplier(tracerWith(false)).current()).isEqualTo("00-t1-s1-00");
    assertThat(supplier(tracerWith(null)).current()).isEqualTo("00-t1-s1-00");
  }

  // Kịch bản: không có span hiện tại hoặc không có Tracer
  // Input: Tracer có currentSpan null, rồi provider trả Tracer null
  // Output: cả hai trả null
  @Test
  void noSpanOrNoTracerGivesNull() {
    Tracer noSpan = mock(Tracer.class);
    assertThat(supplier(noSpan).current()).isNull();
    assertThat(supplier(null).current()).isNull();
  }
}
