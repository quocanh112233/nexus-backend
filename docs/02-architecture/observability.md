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

Mọi service đặt `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-lgtm:4318`. Profile `observability` tắt → exporter bị tắt, log vẫn in JSON ra stdout (`docker compose logs`).

## 2. Log

| Quy ước | Chi tiết |
|--------|---------|
| Định dạng | JSON (Spring Boot structured logging `logging.structured.format.console=ecs`) |
| Field bắt buộc | `timestamp, level, service, traceId, spanId, requestId, userId?, message` |
| Mức | `INFO` sự kiện nghiệp vụ quan trọng (đăng ký, tạo server); `WARN` lỗi phía client lặp lại, message vào DLQ; `ERROR` lỗi hệ thống |
| Cấm | Password, token, chữ ký, nội dung tin nhắn đầy đủ |
| Tìm kiếm | Grafana → Loki: `{service="messaging-service"} |= "traceId=..."`; từ trace nhảy sang log và ngược lại |

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
