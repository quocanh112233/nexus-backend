# Tin nhắn tới client khác qua WebSocket: độ trễ end-to-end (NFR-002)

## Môi trường đo

- CPU: 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz
- Số core: 8 (luồng logic)
- RAM: 20164880 kB (MemTotal)
- Kernel: 7.0.0-38-generic
- Docker: 29.8.2 (build 7fc2dff), Compose 5.6.0
- Commit SHA: f813364 (HEAD lúc đo lần cuối; bản sửa `listeners_verified` và parse CRLF chưa commit lúc đó)
- Ngày đo: 2026-11-16
- k6: `grafana/k6:2.4.0`, tag ghim trong `infra/perf/run.sh`
- Lệnh đã chạy:

```bash
export COMPOSE_PROJECT_NAME=nexus-perf POSTGRES_PORT=55432
COMPOSE_PROJECT=nexus-perf infra/perf/setup.sh --login-only
COMPOSE_PROJECT=nexus-perf infra/perf/run.sh ws-fanout.js; echo "exit=$?"
infra/perf/check-memory.sh nexus-perf
```

## Tham số

- `listeners`: 50 VU (user 0..49), mỗi VU một kết nối `k6/websockets` tới `ws://api-gateway:8080/ws`, `CONNECT` STOMP 1.2 với `Authorization: Bearer`, heartbeat `10000,10000` (client gửi `\n` mỗi 10 s), `SUBSCRIBE /topic/channels/{channelId}`, giữ kết nối 130 s.
- `senders`: 5 VU (user 0..4), bắt đầu sau 5 s, mỗi VU gửi `POST /api/v1/channels/{channelId}/messages` với `content: "fanout <sender>-<lần>"` rồi `sleep(1)` trong 2 phút. Rate limit bật (`RATELIMIT_ENABLED=true`, burst 5, nạp 1 token/giây mỗi user).
- `realtime-gateway` chạy 2 replica nên 50 listener chia trên hai instance; mọi container dưới giới hạn bộ nhớ của D1.
- `e2e_latency` = `Date.now()` trong container k6 lúc nhận message WebSocket trừ `Date.parse(data.createdAt)` của frame `MessageCreated`, chỉ tính frame có `content` bắt đầu bằng `fanout `.
- Tỉ lệ giao: `handleSummary` in `frames_received / (messages_sent * 50)`. Để ngưỡng bắt được tỉ lệ này, cuối bài mỗi listener đọc lịch sử kênh sau tin mốc (id tin mới nhất lấy trong `setup()`), với mỗi tin `fanout ` ghi vào Rate `delivery` là listener đó đã nhận frame của tin hay chưa, rồi tăng Counter `listeners_verified`. Nếu bất kỳ trang lịch sử nào không trả 200 thì listener đó không ghi mẫu `delivery` nào và không tăng `listeners_verified`, nên ngưỡng `count>=50` không đạt; `setup()` tự kiểm hành vi này bằng API giả trước khi đo. Các lần chạy ghi trong file này dùng bản script trước khi có bước chặn đó (khi ấy lỗi đọc lịch sử chỉ làm hỏng một check và listener vẫn được đếm); chưa đo lại sau khi sửa.
- Ngưỡng: `e2e_latency` `p(95)<1000`, `checks` `rate>0.99`, `frames_received` `count>0`, `delivery` `rate>=0.99`, `listeners_verified` `count>=50`. Ba ngưỡng sau để lần chạy không nhận frame nào, hoặc có listener chết trước bước đối chiếu, không thể `exit=0` (ngưỡng trên Trend không có mẫu thì đạt suông).

## Độ chính xác của phép đo

- Đo trên một máy: container k6 và `messaging-service` dùng chung đồng hồ của host, không có lệch đồng hồ giữa bên gửi và bên nhận.
- `createdAt` lấy bằng `clock.instant()` trong `SendMessageUseCase` của `messaging-service` (cắt tới micro giây), nên độ trễ tính từ lúc use case gửi tin chạy, không gồm chặng client → api-gateway → messaging-service trước đó. Độ trễ gồm: ghi DB + outbox, outbox relay, RabbitMQ, realtime-gateway, api-gateway chiều WebSocket; không gồm mạng Internet.
- `Date.parse` cắt `createdAt` về mili giây và `Date.now()` có độ phân giải mili giây, nên mỗi mẫu sai trong khoảng (−1 ms, +1 ms).

## Kết quả

Số lấy từ `infra/perf/.state/ws-fanout.summary.json` của lần chạy cuối với script đã commit (`exit=99`). Đơn vị ms.

| p50 | p90 | p95 | p99 | max |
|---|---|---|---|---|
| 98 | 460 | 493 | 523 | 530 |

- Tin gửi (`messages_sent`, số `201`): 566 trên 590 lần gửi; 24 lần còn lại không phải `201`. Log `messaging-service` trong lúc chạy có 24 dòng `Dependency unavailable, returning 503: ... community circuit breaker is open`.
- Frame nhận (`frames_received`): 18678.
- Tỉ lệ giao: 18678 / (566 × 50) = 0,66; `delivery` 18678/28300.
- 17/50 listener nhận STOMP `ERROR` `DEPENDENCY_UNAVAILABLE` khi subscribe và bị server đóng kết nối (close 1002). Đây là mã `realtime-gateway` trả khi lời gọi gRPC kiểm quyền subscribe ném lỗi (`AccessCheckUnavailableException`); log `realtime-gateway` không ghi nguyên nhân.
- `checks` 94,44% (986/1044), `delivery` 66,00%: hai ngưỡng không đạt. `e2e_latency` p95 493 ms đạt, `listeners_verified` 50 đạt.

### Các lần chạy trong cùng phiên

Ghi đủ để thấy độ dao động giữa các lần, không chọn lần đẹp. Đơn vị ms.

| Lần | Ghi chú | p50 | p95 | max | frame nhận | exit |
|---|---|---|---|---|---|---|
| 1 | bản script còn xin `RECEIPT`, 30/50 listener nhận `DEPENDENCY_UNAVAILABLE` | không ghi lại | 505 | 529 | 11800 | 99 |
| 2 | bỏ `RECEIPT` | 124 | 478 | 529 | 29500 | 0 |
| 3 | như lần 2 | 242 | 507 | 538 | 29500 | 0 |
| 4 | kiểm ngưỡng: `-e DEST=/topic/servers/{serverId}` | không có mẫu | 0 (đạt suông) | không có mẫu | 0 | 99 |
| 5 | kiểm ngưỡng: `-e LISTENER_OFFSET=1`, listener cuối không có user nên chết trước bước đối chiếu | 222 | 480 | 524 | 28910 | 99 |
| 6 | script đã commit, số ở bảng trên | 98 | 493 | 530 | 18678 | 99 |

p50 giữa các lần đầy đủ 50 listener (2, 3, 5) đo được 124, 242 và 222 ms, lần 6 là 98 ms; chưa xác định nguyên nhân chênh lệch. Ở lần 4 chỉ `frames_received` và `delivery` không đạt; ở lần 5 chỉ `listeners_verified` không đạt (49), các ngưỡng khác đều đạt.

## Kết luận NFR-002

KHÔNG ĐẠT. Lần chạy cuối với script đã commit: p95 493 ms dưới ngưỡng 1000 ms, nhưng tỉ lệ giao 0,66 < 0,99 vì 17/50 subscribe bị từ chối `DEPENDENCY_UNAVAILABLE`, và 24/590 lần gửi trả 503 (community circuit breaker mở). Lần 2 và 3 đạt (tỉ lệ giao 1, p95 478 và 507 ms), lần 1 cũng có 30/50 subscribe bị từ chối. Kết luận theo lần chạy cuối, không chọn lần đạt. Chưa điều tra nguyên nhân (D8: P9 chỉ đo).

## Bộ nhớ sau khi chạy

`infra/perf/check-memory.sh nexus-perf` ngay sau lần chạy cuối, exit 0 (không container nào OOMKilled hay restart); `messaging-service` ở 504,5 MiB trên giới hạn 512 MiB.

```text
container                                       usage        limit
nexus-perf-api-gateway-1                     291.3MiB       512MiB
nexus-perf-postgres-1                        295.7MiB       768MiB
nexus-perf-rabbitmq-1                        142.9MiB       512MiB
nexus-perf-redis-1                           13.54MiB       128MiB
nexus-perf-otel-lgtm-1                       695.4MiB         1GiB
nexus-perf-presence-service-1                297.3MiB       512MiB
nexus-perf-realtime-gateway-1                292.8MiB       512MiB
nexus-perf-realtime-gateway-2                302.8MiB       512MiB
nexus-perf-notification-service-1            380.3MiB       512MiB
nexus-perf-community-service-1               421.9MiB       512MiB
nexus-perf-identity-service-1                413.6MiB       512MiB
nexus-perf-messaging-service-1               504.5MiB       512MiB
TOTAL 3.96 GiB
```
