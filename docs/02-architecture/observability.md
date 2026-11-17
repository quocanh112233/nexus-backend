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
| Queue | `rabbitmq_detailed_queue_messages_ready` (nhãn `queue`, gồm cả `*.dlq`) | RabbitMQ plugin, endpoint `/metrics/detailed?family=queue_coarse_metrics` |
| Nghiệp vụ | `nexus_messages_sent_total`, `nexus_ws_sessions` (gauge), `nexus_permission_cache_hits_total{result}` | Tự đo |
| JVM | heap, GC, thread | Actuator |

Dashboard "Nexus overview" và 3 alert: xem mục 7.

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

Không làm: alerting phức tạp, SLO, log sampling. Chỉ có 3 alert đơn giản ở mục 7, chưa có kênh gửi.

## 7. Dashboard và alert (P9)

Mọi file nằm trong `infra/observability/` và được `infra/compose.infra.yaml` mount vào `otel-lgtm` (image `grafana/otel-lgtm:0.39.0`), nên khởi động lại container là có đủ, không cấu hình tay trong UI.

| File | Mount vào | Vai trò |
|------|-----------|---------|
| `prometheus.yaml` | `/otel-lgtm/prometheus.yaml` (thay bản gốc) | Bản gốc của image kèm job `rabbitmq` scrape `rabbitmq:15692/metrics/detailed?family=queue_coarse_metrics` mỗi 15 giây; cổng 15692 chỉ có trong network compose, không publish ra host |
| `dashboards/nexus-overview.json` | `/otel-lgtm/nexus-overview.json` | Dashboard `nexus-overview`, biến `service` (`service_name`, multi, mặc định All) |
| `provisioning/dashboards.yaml` | `/otel-lgtm/grafana/conf/provisioning/dashboards/nexus.yaml` | Provider nạp file JSON trên |
| `provisioning/alerts.yaml` | `/otel-lgtm/grafana/conf/provisioning/alerting/nexus.yaml` | Nhóm `nexus` (folder `Nexus`, đánh giá mỗi 30 giây) |

Plugin `rabbitmq_prometheus` đã bật sẵn trong image `rabbitmq:4-management-alpine`. Endpoint `/metrics` mặc định chỉ trả số tổng không có nhãn `queue`; số liệu từng queue phải lấy ở `/metrics/detailed` và có tên `rabbitmq_detailed_queue_messages_ready`.

Panel (đều lọc theo biến `service` nếu có nhãn `service_name`): request/giây, HTTP p95, tỉ lệ 5xx, outbox pending, độ sâu từng queue, DLQ, tỉ lệ hit cache quyền, heap JVM. Nhãn thật trên Prometheus: `status` (không phải `http_response_status_code`), `area` (không phải `jvm_memory_type`), `queue`.

| Alert | Điều kiện | Giữ trong |
|-------|-----------|-----------|
| `OutboxBacklog` | `nexus_outbox_pending > 100` | 2 phút |
| `Http5xxRate` | tỉ lệ `status=~"5.."` > 5% trên bất kỳ service | 5 phút |
| `DeadLetterNotEmpty` | `rabbitmq_detailed_queue_messages_ready{queue=~".*\\.dlq"} > 0` | 1 phút |

`noDataState: OK`: không có series (chưa có lỗi 5xx, chưa có queue) nghĩa là không báo. Tỉ lệ 5xx dùng `rate()` nên lần 5xx đầu tiên của một service chưa có series 5xx không được tính.

Kiểm thật trên stack `nexus-perf` (2026-11-16, OTLP bật): dừng `rabbitmq` lúc 22:19:10 rồi chạy k6 gửi tin 2 phút; `nexus_outbox_pending` của `messaging-service` lên 1350, 4300, 5950 (gauge chỉ cập nhật mỗi ~60 giây), `OutboxBacklog` sang `pending` khoảng 22:20:30 và `firing` lúc 22:22:22. Bật lại `rabbitmq` lúc 22:23:20, outbox về 0 lúc 22:24:47 và alert hết `firing` trước 22:25:19.

Lưu ý khi vận hành:

- Metric ứng dụng đến Prometheus qua OTLP mỗi ~60 giây nên cửa sổ `rate()` phải từ 2 phút trở lên; `rate(...[1m])` luôn rỗng.
- API `/api/prometheus/grafana/api/v1/alerts` của bản Grafana này gọi trạng thái báo động là `Alerting`, không phải `firing`; lọc `select(.state=="firing")` sẽ luôn ra 0. Dùng `/api/prometheus/grafana/api/v1/rules` (trường `state`) hoặc lọc `Alerting`.
- Khi `rabbitmq` dừng, endpoint scrape chết, `DeadLetterNotEmpty` không còn dữ liệu nên chuyển về `OK` do `noDataState: OK` (DLQ đang có message vẫn không báo trong lúc broker sập); `OutboxBacklog` mới là alert bắt tình huống đó.
