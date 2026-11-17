# Gửi tin nhắn qua API: p95 với 50 người dùng (NFR-001)

## Môi trường đo

- CPU: 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz
- Số core: 8 (luồng logic)
- RAM: 20164884 kB (MemTotal)
- Kernel: 7.0.0-38-generic
- Docker: 29.8.2 (build 7fc2dff), Compose 5.6.0
- Commit SHA: ccb2f9b (HEAD lúc đo; script k6 và `run.sh` của Task 4 chưa commit lúc đó)
- Ngày đo: 2026-11-16
- k6: `grafana/k6:2.4.0` (k6 v2.4.0, commit dd9395906d, go1.27.3), tag ghim trong `infra/perf/run.sh`
- Lệnh đã chạy:

```bash
export COMPOSE_PROJECT_NAME=nexus-perf POSTGRES_PORT=55432
COMPOSE_PROJECT=nexus-perf infra/perf/setup.sh --login-only
COMPOSE_PROJECT=nexus-perf infra/perf/run.sh send-message.js; echo "exit=$?"
infra/perf/check-memory.sh nexus-perf
```

## Tham số

- 50 VU (`constant-vus`), 2 phút, mỗi VU dùng token của một user riêng (50 user trong `state.json`), gửi 1 tin mỗi giây (`sleep(1)`).
- Rate limit của api-gateway bật (`RATELIMIT_ENABLED=true`, bucket gửi tin burst 5, nạp 1 token/giây mỗi user), cache quyền bật (`PERMISSION_CACHE_ENABLED=true`).
- Gọi `POST /api/v1/channels/{channelId}/messages` vào kênh đã seed 1 triệu tin, qua `http://api-gateway:8080` từ container k6 trong network của stack; `realtime-gateway` 2 replica, mọi container dưới giới hạn bộ nhớ của D1.
- Ngưỡng: `http_req_duration{name:send}` `p(95)<150`, `checks` `rate>0.99`. Mỗi request kiểm mã `201` và body trả về đúng `channelId`, đúng `content`, có `id`.

## Kết quả

Chạy hai lần liên tiếp cùng cấu hình, mỗi lần sau `setup.sh --login-only`; số lấy từ summary của k6 (lần 2 từ `infra/perf/.state/send-message.summary.json`, lần 1 từ bảng tổng kết in ra). Đơn vị ms.

| Lần chạy | Request | p50 | p90 | p95 | p99 | max | req/s | tỉ lệ lỗi |
|---|---|---|---|---|---|---|---|---|
| 1 (chạy trước, chưa xuất p99) | 5834 | 11,24 | 31,41 | 61,33 | không có | 1770 | 48,213 | 0% (0/5834) |
| 2 (chạy sau, bản cuối của `run.sh`) | 5900 | 10,075 | 18,806 | 24,365 | 299,518 | 970,881 | 48,791 | 0% (0/5900) |

Cả hai lần: `exit=0`, mọi check đạt (lần 1: 11668/11668, lần 2: 11800/11800), không mã 429. Lần 1 chạy trước khi thêm `--summary-trend-stats` có `p(99)` vào `run.sh` nên không có p99 và chỉ có số làm tròn do k6 in (max 1,77 s); lần 2 chạy lại sau khi thêm để có đủ cột. Hai lần chênh nhau ở đuôi phân phối (p95 61,33 so với 24,365 ms), nên số đo đuôi dao động theo lần chạy trên máy này.

## Kết luận NFR-001

ĐẠT. Kết luận dựa trên lần 1 là lần chạy chậm hơn (p95 61,33 ms), vì ngưỡng cần đúng ở trường hợp xấu và không chọn lần đẹp hơn; lần 2 (p95 24,365 ms) cho cùng kết luận. Cả hai đều dưới ngưỡng 150 ms với 50 người dùng đồng thời và không request nào bị rate limit hay lỗi. p99 299,518 ms và max 970,881 ms (lần 2) cho thấy đuôi chậm hiếm gặp; chưa điều tra nguyên nhân (D8: P9 chỉ đo).

## Bộ nhớ sau khi chạy

`infra/perf/check-memory.sh nexus-perf` ngay sau lần chạy 2, exit 0 (không container nào OOMKilled hay restart); `messaging-service` ở 490,1 MiB trên giới hạn 512 MiB.

```text
container                                       usage        limit
nexus-perf-api-gateway-1                     299.7MiB       512MiB
nexus-perf-postgres-1                        300.2MiB       768MiB
nexus-perf-rabbitmq-1                        139.6MiB       512MiB
nexus-perf-redis-1                            13.2MiB       128MiB
nexus-perf-otel-lgtm-1                       690.1MiB         1GiB
nexus-perf-presence-service-1                271.5MiB       512MiB
nexus-perf-realtime-gateway-1                229.8MiB       512MiB
nexus-perf-realtime-gateway-2                224.8MiB       512MiB
nexus-perf-notification-service-1            375.2MiB       512MiB
nexus-perf-community-service-1               404.3MiB       512MiB
nexus-perf-identity-service-1                399.5MiB       512MiB
nexus-perf-messaging-service-1               490.1MiB       512MiB
TOTAL 3.75 GiB
```
