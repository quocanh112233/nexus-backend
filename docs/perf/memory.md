# Bộ nhớ lúc rỗi (NFR-005)

## Môi trường đo

- CPU: 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz
- Số core: 8 (luồng logic)
- RAM: 20164884 kB (MemTotal)
- Kernel: 7.0.0-38-generic
- Docker: 29.8.2 (build 7fc2dff), Compose 5.6.0
- Commit SHA: 95a15a4 (nền của nhánh feat/p9-hardening; số đo lấy trên cây làm việc đã áp dụng thay đổi Task 1)
- Ngày đo: 2026-11-15
- Lệnh đã chạy:

```bash
export COMPOSE_PROJECT_NAME=nexus-perf POSTGRES_PORT=55432
docker compose --profile observability up -d --wait
COMPOSE_PROJECT=nexus-perf infra/scripts/demo-p8.sh
infra/perf/check-memory.sh nexus-perf
```

## Kết quả

Đo ngay sau khi demo P8 chạy xong (tải nhẹ, 3 user, vài tin nhắn). Có 12 container vì `realtime-gateway` chạy 2 replica, mỗi replica chịu giới hạn 512m riêng.

```text
container                                       usage        limit
nexus-perf-api-gateway-1                     247.8MiB       512MiB
nexus-perf-postgres-1                        84.71MiB       768MiB
nexus-perf-rabbitmq-1                        99.23MiB       512MiB
nexus-perf-redis-1                           3.715MiB       128MiB
nexus-perf-otel-lgtm-1                       473.2MiB         1GiB
nexus-perf-presence-service-1                261.3MiB       512MiB
nexus-perf-realtime-gateway-1                220.7MiB       512MiB
nexus-perf-realtime-gateway-2                210.8MiB       512MiB
nexus-perf-notification-service-1            365.4MiB       512MiB
nexus-perf-community-service-1               385.7MiB       512MiB
nexus-perf-identity-service-1                352.2MiB       512MiB
nexus-perf-messaging-service-1               458.2MiB       512MiB
TOTAL 3.09 GiB
```

Tổng usage 3.09 GiB, thấp hơn ngưỡng 6 GiB; không container nào bị OOMKilled hoặc restart. `messaging-service` dùng 458.2MiB trên giới hạn 512MiB, là service sát giới hạn nhất.

Tổng giới hạn thực của 12 container là 6528 MiB (6,375 GiB) vì `realtime-gateway` chạy 2 replica; NFR-005 xét theo tổng usage đo được (3.09 GiB), không xét tổng giới hạn.
