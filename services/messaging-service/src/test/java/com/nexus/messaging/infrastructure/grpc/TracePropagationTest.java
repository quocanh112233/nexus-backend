package com.nexus.messaging.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.proto.community.v1.CheckChannelPermissionsRequest;
import com.nexus.proto.community.v1.CheckChannelPermissionsResponse;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Kiểm tra traceparent đi từ kênh client của messaging sang server Community qua metadata gRPC
class TracePropagationTest {

  private static final String RPC = "nexus.community.v1.PermissionService/CheckChannelPermissions";

  private final List<SpanData> spans = new CopyOnWriteArrayList<>();
  private Server server;
  private ManagedChannel channel;

  // Dựng tracer OTel ghi span vào danh sách, server có interceptor observation, kênh qua configure
  @BeforeEach
  void setUp() throws Exception {
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(new CollectingProcessor()).build();
    io.opentelemetry.api.trace.Tracer otel = provider.get("test");
    ContextPropagators propagators =
        ContextPropagators.create(W3CTraceContextPropagator.getInstance());
    OtelTracer tracer =
        new OtelTracer(
            otel,
            new OtelCurrentTraceContext(),
            event -> {},
            new io.micrometer.tracing.otel.bridge.OtelBaggageManager(
                new OtelCurrentTraceContext(), List.of(), List.of()));
    OtelPropagator propagator = new OtelPropagator(propagators, otel);
    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler.FirstMatchingCompositeObservationHandler(
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));
    String name = "community-" + UUID.randomUUID();
    server =
        InProcessServerBuilder.forName(name)
            .addService(new EchoPermissionService())
            .intercept(new ObservationGrpcServerInterceptor(registry))
            .build()
            .start();
    channel =
        CommunityChannelConfig.configure(InProcessChannelBuilder.forName(name), registry).build();
  }

  // Tắt kênh và server sau mỗi test
  @AfterEach
  void tearDown() throws Exception {
    channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
  }

  // Gọi CheckChannelPermissions rồi chờ đủ hai span client và server kết thúc
  private void callAndAwaitSpans() throws InterruptedException {
    PermissionServiceGrpc.newBlockingStub(channel)
        .checkChannelPermissions(CheckChannelPermissionsRequest.getDefaultInstance());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (spans.size() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(spans).hasSize(2);
  }

  // Kịch bản: một call gRPC từ messaging sang community
  // Input: CheckChannelPermissions qua kênh dựng bằng CommunityChannelConfig.configure
  // Output: hai span cùng traceId, span server nhận span client làm cha
  @Test
  void clientAndServerSpansShareTraceId() throws Exception {
    callAndAwaitSpans();

    SpanData client =
        spans.stream().filter(s -> s.getKind().name().equals("CLIENT")).findFirst().orElseThrow();
    SpanData serverSpan =
        spans.stream().filter(s -> s.getKind().name().equals("SERVER")).findFirst().orElseThrow();
    assertThat(serverSpan.getTraceId()).isEqualTo(client.getTraceId());
    assertThat(serverSpan.getParentSpanId()).isEqualTo(client.getSpanId());
  }

  // Kịch bản: đặt tên span theo RPC
  // Input: CheckChannelPermissions qua kênh dựng bằng CommunityChannelConfig.configure
  // Output: tên cả hai span đều là RPC nexus.community.v1.PermissionService/CheckChannelPermissions
  @Test
  void serverSpanNamedAfterRpc() throws Exception {
    callAndAwaitSpans();

    assertThat(spans).extracting(SpanData::getName).containsOnly(RPC);
  }

  // Processor gom span đã kết thúc vào danh sách của test
  private final class CollectingProcessor implements SpanProcessor {

    // Không cần xử lý lúc span bắt đầu
    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {}

    // Không yêu cầu callback bắt đầu
    @Override
    public boolean isStartRequired() {
      return false;
    }

    // Lưu dữ liệu span khi kết thúc
    @Override
    public void onEnd(ReadableSpan span) {
      spans.add(span.toSpanData());
    }

    // Yêu cầu callback kết thúc
    @Override
    public boolean isEndRequired() {
      return true;
    }
  }

  // Service Community giả luôn trả response rỗng
  private static final class EchoPermissionService
      extends PermissionServiceGrpc.PermissionServiceImplBase {

    // Trả response mặc định ngay
    @Override
    public void checkChannelPermissions(
        CheckChannelPermissionsRequest request,
        StreamObserver<CheckChannelPermissionsResponse> observer) {
      observer.onNext(CheckChannelPermissionsResponse.getDefaultInstance());
      observer.onCompleted();
    }
  }
}
