# Observability (log, metric, trace)

File này mô tả cách quan sát hệ thống ở mức đủ học và đủ demo phỏng vấn: log tập trung, metric, distributed trace xuyên REST → gRPC → RabbitMQ → WebSocket. Quyết định: P-20.

## 1. Kiến trúc

| Thành phần | Vai trò |
|-----------|---------|
| Micrometer (Spring Boot Actuator) | Metric trong mỗi service |
| Micrometer Tracing + OpenTelemetry bridge | Tạo span, lan truyền `traceparent` qua HTTP, gRPC, AMQP |
| OpenTelemetry Logback appender | Gửi log (có `traceId`) qua OTLP |
| `grafana/otel-lgtm` (1 container, profile `observability`) | OTel Collector nhận OTLP → Loki (log), Tempo (trace), Prometheus (metric) → Grafana UI `http://localhost:3001` |
| RabbitMQ Prometheus plugin | Metric queue (độ dài, tốc độ, unacked) |

Bật bằng `OTEL_ENDPOINT=http://otel-lgtm:4318 docker compose --profile observability up -d --build --wait` (compose map `OTEL_ENDPOINT` vào `OTEL_EXPORTER_OTLP_ENDPOINT` của từng service). `OTEL_ENDPOINT` trống → exporter log, trace, metric đều tắt, log vẫn in JSON ra stdout (`docker compose logs`).

## 2. Log

| Quy ước | Chi tiết |
|--------|---------|
| Định dạng | JSON (Spring Boot structured logging `logging.structured.format.console=ecs`) |
| Field bắt buộc | `service.name` (Loki label `service_name`), `requestId`, `traceId`, `spanId`, `level`, `message`, thời gian; `userId` chưa được ghi vì chưa có MDC `userId` (dự kiến P3) |
| Mức | `INFO` sự kiện nghiệp vụ quan trọng (đăng ký, tạo server); `WARN` lỗi phía client lặp lại, message vào DLQ; `ERROR` lỗi hệ thống |
| Cấm | Password, token, chữ ký, nội dung tin nhắn đầy đủ |
| Tìm kiếm | Grafana → Loki: `{service_name="community-service"} | requestId="..."` hoặc `{service_name=~".+"} | traceId="..."`; từ trace nhảy sang log và ngược lại |

### Cài đặt P2

| Mục | Giá trị |
|-----|---------|
| Dependency | `libs/nexus-common-observability` (cả identity, community, api-gateway dùng): `spring-boot-starter-opentelemetry` (Boot 4.1.2, kéo `micrometer-registry-otlp`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`) + `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.0-alpha` |
| Log | `logback-spring.xml` trong lib: console JSON (ECS) + `OpenTelemetryAppender` (`captureMdcAttributes=*`), nối vào `OpenTelemetry` bean bằng `OtelLogAppenderAutoConfiguration` |
| Bật/tắt | `OtelExportEnvironmentPostProcessor`: `OTEL_EXPORTER_OTLP_ENDPOINT` (hoặc `otel.exporter.otlp.endpoint`) trống hoặc không đặt → `management.otlp.metrics.export.enabled`, `management.tracing.export.otlp.enabled`, `management.logging.export.otlp.enabled` = `false` (test và compose không profile không kết nối đi đâu; cờ đặt tường minh ở nơi khác như system property, env, yml thắng vì property source này ưu tiên thấp nhất); có giá trị thì Boot tự thêm `/v1/logs`, `/v1/traces`, `/v1/metrics` |
| Sampling | `management.tracing.sampling.probability=1.0` trong `application.yml` mỗi service |
| Resource | `service.name` = `spring.application.name` (`identity-service`, `community-service`, `api-gateway`); Loki gắn thành label `service_name` |
| Log theo request | Service servlet ghi một dòng `method path status ms` trong `RequestIdFilter` (MDC `requestId`, `traceId`); gateway ghi `requestId=...` trong chính dòng log của `RequestIdFilter` (reactive không có MDC) |
| Tên metric | OTLP dùng đơn vị mili giây: `http_server_requests_milliseconds_count` thay cho `http_server_requests_seconds_count` ở bảng dưới |

Mục tiêu "log của 3 thành phần cho một request" chờ P4 (chưa có service gọi service): hiện thấy api-gateway và service đích, cùng `traceId`.

## 3. Metric

| Nhóm | Metric | Nguồn |
|-----|--------|------|
| REST (RED) | `http_server_requests_seconds` (count, sum, bucket theo `uri`, `status`) | Actuator |
| gRPC | `grpc_server_*`, `grpc_client_*` (duration, status) | Spring gRPC + Micrometer |
| Circuit breaker | `resilience4j_circuitbreaker_state` | Resilience4j |
| Consumer | `spring_rabbitmq_listener_seconds` (theo queue, kết quả) | Spring AMQP |
| Outbox | `nexus_outbox_pending` (gauge số dòng chưa gửi), `nexus_outbox_publish_seconds` | Tự đo |
| Queue | `rabbitmq_queue_messages_ready`, `..._unacked`, DLQ | RabbitMQ plugin |
| Nghiệp vụ | `nexus_messages_sent_total`, `nexus_ws_sessions` (gauge), `nexus_permission_cache_hits_total{result}` | Tự đo |
| JVM | heap, GC, thread | Actuator |

Dashboard "Nexus overview" (JSON commit tại `infra/observability/dashboards/`): tốc độ request + lỗi + p95 mỗi service; gRPC p95; trạng thái circuit breaker; outbox pending; độ dài queue + DLQ; số WebSocket session.

## 4. Trace

Một trace mẫu cho "gửi tin nhắn" (demo phỏng vấn):

```text
api-gateway  POST /api/v1/channels/{id}/messages
└─ messaging-service  POST /channels/{id}/messages
   ├─ gRPC client community.v1.PermissionService/CheckChannelPermissions
   │  └─ community-service gRPC server + SQL
   ├─ SQL INSERT messages / outbox_event
   └─ (bất đồng bộ, link qua traceparent trong outbox headers)
      ├─ outbox relay publish messaging.events message.created
      ├─ notification-service consume + SQL
      └─ realtime-gateway consume → STOMP send /topic/channels/{id}
```

Outbox lưu `traceparent` của request gốc vào cột `headers` → relay đặt vào header AMQP → consumer tiếp tục cùng trace. Sampling: 100% ở local, 10% ở demo công khai.

## 5. Health

| Endpoint | Dùng cho |
|---------|---------|
| `/actuator/health/liveness` | Process còn sống |
| `/actuator/health/readiness` | Sẵn sàng nhận traffic (DB, broker kết nối được) — compose `healthcheck` |
| `/actuator/prometheus` | Scrape (khi không dùng OTLP metric) |

Actuator chạy trên cổng quản trị riêng (`management.server.port`) không route qua gateway.

## 6. Phạm vi vừa sức

Không làm: alerting phức tạp, SLO, log sampling. Có thể thêm 2-3 alert Grafana đơn giản (DLQ > 0, circuit breaker open, p95 > 500 ms) ở P9.
