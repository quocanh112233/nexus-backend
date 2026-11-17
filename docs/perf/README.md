# Đo hiệu năng và bộ nhớ (P9)

## Mục lục

- [memory.md](memory.md): usage và limit từng container lúc rỗi, tổng so với ngưỡng 6 GiB (NFR-005)
- [history.md](history.md): seed 1 triệu tin, `EXPLAIN ANALYZE` keyset so với OFFSET ở bốn độ sâu, trên bảng một kênh và bảng nhiều kênh, kèm lần đo qua API bằng k6 (NFR-003)
- [send-message.md](send-message.md): k6 gửi tin với 50 VU trong 2 phút, p95 so với ngưỡng 150 ms (NFR-001)
- [ws-fanout.md](ws-fanout.md): k6 50 listener STOMP và 5 sender trong 2 phút, độ trễ end-to-end qua outbox, RabbitMQ, WebSocket so với ngưỡng p95 1 s và tỉ lệ giao (NFR-002)
- [rate-limit.md](rate-limit.md): 20 tin liên tiếp của một user, số 201 và 429, user khác không bị ảnh hưởng (NFR-013)
- [permission-cache.md](permission-cache.md): gửi tin 50 VU với cache quyền Redis bật so với tắt, số gRPC call tới Community và p95
- [resilience.md](resilience.md): tắt Community, publish lại event đã xử lý, kill notification-service giữa lúc gửi tin

Mỗi file số liệu trong thư mục này mở đầu bằng khối "Môi trường đo" theo mẫu bên dưới. Số liệu là kết quả thật của lần chạy ghi trong khối đó, không làm tròn và không chỉnh ngưỡng để qua.

## Môi trường đo (mẫu)

- CPU: `<lscpu | grep 'Model name'>`
- Số core: `<nproc>`
- RAM: `<MemTotal trong /proc/meminfo>`
- Kernel: `<uname -r>`
- Docker: `<docker --version>`, Compose `<docker compose version --short>`
- Commit SHA: `<git rev-parse --short HEAD>`
- Ngày đo: `<YYYY-MM-DD>`
- Lệnh đã chạy: `<lệnh đầy đủ>`

## Stack đo

Stack đo luôn chạy với project riêng `nexus-perf` để không đụng volume `nexus_pgdata` của dev. Nếu cổng 5432 trên host bị chiếm thì đặt thêm `POSTGRES_PORT=55432`.

Khởi động:

```bash
export COMPOSE_PROJECT_NAME=nexus-perf POSTGRES_PORT=55432 OTEL_ENDPOINT=http://otel-lgtm:4318
docker compose --profile observability up -d --build --wait
```

`OTEL_ENDPOINT` là biến shell (không sửa `.env`); thiếu biến này thì không có metric nào tới Prometheus nên dashboard, alert và cách đo của [permission-cache.md](permission-cache.md) không tái hiện được. Các phép đo trước cache quyền (bộ nhớ, lịch sử, gửi tin, fan-out WebSocket) chạy với xuất OTLP tắt.

`infra/perf/setup.sh` (kể cả `--login-only`) chạy `docker compose up api-gateway` và sẽ tạo lại các service phụ thuộc nếu biến đã export trong shell khác với lúc container được khởi động (ví dụ `PERMISSION_CACHE_ENABLED`, `OTEL_ENDPOINT`), kéo theo JVM nguội và sai cấu hình. Giữ nguyên các lệnh `export` như lúc khởi động ở mọi shell chạy script perf.

Quy tắc làm nóng: sau mỗi lần container được tạo mới hoặc tạo lại, chạy một lần làm nóng đúng script k6 sẽ đo, ghi kết quả lần đó vào tài liệu và không dùng nó cho kết luận. Lần đo bị lỗi vẫn ghi nguyên số liệu, không chạy lại cho tới khi đạt, và ghi đủ mọi lần đã chạy.

Kiểm tổng bộ nhớ (exit 1 nếu tổng usage > 6 GiB hoặc có container bị OOMKilled hoặc restart):

```bash
infra/perf/check-memory.sh nexus-perf
```

Dừng stack khi đo xong, giữ container và volume:

```bash
docker compose -p nexus-perf stop
```

Không chạy `down` (có hoặc không `-v`) và không xóa container `nexus-perf-rabbitmq-1`: dữ liệu RabbitMQ nằm trong volume ẩn danh, nên làm vậy mất 79 message DLQ của presence chưa được điều tra. Chỉ gỡ hẳn stack sau khi điều tra xong, theo quyết định của chủ repo.

## Giới hạn bộ nhớ

Giới hạn đặt trong `infra/compose.app.yaml` và `infra/compose.infra.yaml`: 7 service JVM `512m` mỗi service, `postgres` 768m, `rabbitmq` 512m, `redis` 128m, `otel-lgtm` 1g, `realtime-gateway` chạy 2 replica nên có 12 container, tổng giới hạn thực là 6528 MiB (6,375 GiB); NFR-005 xét theo usage `docker stats` đo được, phải không quá 6 GiB. Mọi phép đo chạy dưới các giới hạn này.

## Công tắc cấu hình

`RATELIMIT_ENABLED` (api-gateway) và `PERMISSION_CACHE_ENABLED` đều mặc định `true`; chỉ đặt khi cần đo riêng một tính năng.
