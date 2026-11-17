# Thí nghiệm độ bền: tắt Community, event trùng, kill consumer

## Môi trường đo

- CPU: 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz
- Số core: 8 (luồng logic)
- RAM: 20164876 kB (MemTotal)
- Kernel: 7.0.0-43-generic
- Docker: 29.10.0 (build fdd4b8e), Compose 5.7.1
- Commit SHA: dbebd3e
- Ngày đo: 2026-11-17 (giờ trong file là UTC)
- Xuất OTLP: BẬT (`OTEL_ENDPOINT=http://otel-lgtm:4318`). Các lần đo của Task 1-5 chạy với OTLP TẮT.
- k6: `grafana/k6:2.4.0` (tag ghim trong `infra/perf/run.sh`)
- Stack: project `nexus-perf`, 12/12 container healthy, khởi động vài phút trước thí nghiệm 1 (JVM còn nguội).
- Lệnh đã chạy (mọi lệnh chạy trong shell đã export ba biến dưới, kèm `COMPOSE_PROJECT=nexus-perf` cho script perf):

```bash
export COMPOSE_PROJECT_NAME=nexus-perf POSTGRES_PORT=55432 OTEL_ENDPOINT=http://otel-lgtm:4318
COMPOSE_PROJECT=nexus-perf infra/perf/setup.sh --login-only
docker compose --profile observability stop community-service
docker compose --profile observability start community-service
curl -sS -w '%{http_code} %{time_total}\n' -X POST http://127.0.0.1:8080/api/v1/channels/<channelId>/messages ...
curl -X POST http://127.0.0.1:15672/api/exchanges/nexus/messaging.events/publish ...
COMPOSE_PROJECT=nexus-perf infra/perf/run.sh send-message.js --vus 48 --duration 2m
docker compose --profile observability kill notification-service
docker compose --profile observability start notification-service
```

Tài khoản RabbitMQ Management lấy từ biến môi trường của container `rabbitmq` vào biến shell, truyền cho curl qua stdin, không in ra. User và kênh lấy từ `infra/perf/.state/state.json` (50 user cùng một kênh, `users[0]` là chủ server).

## 1. Tắt Community khi user chưa có cache quyền

Cách làm: `users[20]` gửi tin lần cuối lúc 2026-11-16 15:42:54, lâu hơn TTL 60 s của cache quyền; trước khi bắt đầu, Redis không có key `perm:*` nào (`redis-cli EXISTS perm:<channelId>:<users[20]>` trả `0`). Dừng `community-service` lúc 05:31:11 rồi gửi tin bằng `users[20]`.

| Lúc | Trạng thái Community | HTTP | Mã lỗi | `time_total` (s) | `ms` trong log messaging |
|---|---|---|---|---|---|
| 05:31:12 | đã dừng | 503 | `DEPENDENCY_UNAVAILABLE` | 1.323668 | 695 |
| 05:31:14 | đã dừng | 503 | `DEPENDENCY_UNAVAILABLE` | 0.188863 | 165 |
| 05:31:45 | healthy (từ 05:31:45) | 503 | `DEPENDENCY_UNAVAILABLE` | 0.177382 | 159 |
| 05:31:46 | healthy | 503 | `DEPENDENCY_UNAVAILABLE` | 0.168875 | 152 |
| 05:31:59 | healthy | 503 | `DEPENDENCY_UNAVAILABLE` | 0.352053 | 334 |
| 05:32:01 | healthy | 201 | không | 0.316018 | 293 |

Body của lần 503 đầu: `{"type":"https://nexus.dev/errors/dependency-unavailable","title":"Dependency unavailable","status":503,"code":"DEPENDENCY_UNAVAILABLE","detail":"Dependency temporarily unavailable",...}`. Cả năm lần 503 có dòng log messaging `Dependency unavailable, returning 503: ...DependencyUnavailableException: community call failed`, không lần nào là `circuit breaker is open`.

Quan sát:

- Khi Community chết, gửi tin trả 503 có mã `DEPENDENCY_UNAVAILABLE`, không treo. Lần đầu 1,32 s là request đầu tiên tới messaging sau khi stack khởi động (log có `Initializing Spring DispatcherServlet` ngay trước) và đi qua api-gateway vừa được `setup.sh` tạo lại; lần thứ hai 0,19 s.
- `docker compose start community-service` lúc 05:31:29, container healthy sau 16 s (05:31:45; log Community: gRPC server listen `:9092` lúc 05:31:41.539). Ba lần gửi sau khi Community healthy vẫn 503 `community call failed` (05:31:45, 05:31:46, 05:31:59); lần gửi lúc 05:32:01 mới 201, tức khoảng 16 s sau khi healthy và 20 s sau khi gRPC server listen. Sau lần 201, Redis có key cache của `users[20]` (TTL còn 53 s).
- Nguyên nhân của ba lần 503 sau khi Community đã lên chưa xác định: messaging không log mã gRPC gốc (cùng điểm mù đã ghi ở [ws-fanout.md](ws-fanout.md)). Không đo thêm.

## 2. Publish lại một `MessageCreated` đã xử lý

Cách làm: `users[1]` gửi tin `resilience-2 <@users[2]>` (201, id `01a1e7fd-ea50-7f30-8f84-d97c933adc77`); notification xử lý xong trong 2 s (`mentionCount` của `users[2]` từ 0 lên 1). Lấy đúng dòng outbox của tin đó (`messaging.outbox_event`, `eventId` `01a1e7fd-ea57-78d1-9dcc-c07b16d955ee`, `published_at` 05:32:19.994), dựng lại envelope như `OutboxRelay` (cùng `eventId`, `eventType`, `payload`, header `traceparent`, `message_id` = `eventId`) rồi `POST /api/exchanges/nexus/messaging.events/publish` với routing key `message.created`. Làm hai lần.

| Lần | Lúc publish | Kết quả API | `notification.messaging.events` publish/deliver/ack trước → sau | read state `users[2]` trước | read state `users[2]` sau 10 s |
|---|---|---|---|---|---|
| 1 | 05:32:38 | `{"routed":true}`, HTTP 200 | không chụp trước; sau: queue 0 message | `mentionCount=1`, `unread=true` | `mentionCount=1`, `unread=true` |
| 2 | 05:33:00 | `{"routed":true}`, HTTP 200 | 3/3/3 → 4/4/4, `redeliver` 0 | `mentionCount=1`, `unread=true` | `mentionCount=1`, `unread=true` |

`lastMessageId` (`01a1e7fd-ea50-...`) và `lastReadMessageId` (`01a1e506-90d1-...`) của `users[2]` không đổi qua cả hai lần. Sau hai lần publish: `notification.processed_event` có đúng 1 dòng cho `eventId` đó (`processed_at` 05:32:19.995, của lần xử lý gốc), `notification.mentions` có đúng 1 dòng cho tin đó, `notification.messaging.events.dlq` 0 message.

Quan sát: lần 2 cho thấy message trùng thật sự được giao tới notification và được ack (bộ đếm 3 → 4), nhưng inbox `processed_event` bỏ qua nên `mentionCount` và unread không đổi. Exchange `messaging.events` còn route `message.created` vào hai queue `realtime-gateway.*` (binding `#`); hai queue đó về 0 sau đó; lúc thí nghiệm không có client WebSocket nào nên không quan sát được frame trùng phía client (realtime-gateway không có inbox, đây là hành vi at-least-once đã biết).

## 3. Kill notification-service khi đang gửi tin

### Làm nóng (R12, không dùng cho kết luận)

Ngay sau `setup.sh --login-only` (tạo lại api-gateway), chạy `run.sh send-message.js --vus 48 --duration 2m` lúc 05:34:03-05:36:05: `exit=0`, 5553 request, 0% lỗi, checks 11106/11106, p95 97.58 ms, p99 288.72 ms, max 1.91 s.

### Lần đo

`send-message.js` chạy với `--vus 48 --duration 2m` (cờ CLI của k6 thay scenario 50 VU trong script) để `users[48]` và `users[49]` rảnh: trong lúc k6 chạy, `users[48]` gửi 40 tin `resilience-3 <i> <@users[49]>`, mỗi 2 s một tin, để có một con số đếm được ở phía notification (`mentionCount` của `users[49]`, người không gửi tin nào nên mốc đọc không bị đẩy). Tin của k6 không có mention nên chỉ đếm được qua DB.

Dòng thời gian (UTC):

- 05:36:30.64: k6 bắt đầu (T0).
- 05:36:50: tin mention đầu tiên.
- 05:37:10.65: `docker compose kill notification-service`.
- 05:37:40.94: `docker compose start notification-service`; log: backfill xong rồi `Started` lúc 05:37:52.6; container healthy lúc 05:37:56.
- 05:38:09: tin mention thứ 40.
- 05:38:32.34: k6 kết thúc (T1); queue `notification.messaging.events` đã về 0 ngay lúc kiểm.

Độ sâu queue `notification.messaging.events` (management API, lấy mẫu mỗi 2 s, `[messages, unacked, consumers]`; số của management API làm mới khoảng 5 s một lần nên lặp lại):

```text
05:37:16 [0,0,1]  05:37:18 [118,0,0]  05:37:23 [340,0,0]  05:37:29 [582,0,0]  05:37:33 [825,0,0]
05:37:39 [1067,0,0]  05:37:43 [1310,0,0]  05:37:49 [1552,0,0]  05:37:53 [1789,0,0]
05:37:59 [2014,20,1]  05:38:03 [1310,20,1]  05:38:09 [147,20,1]  05:38:13 [0,0,1]
```

Kết quả k6 của lần đo: `exit=0`, 5693 request, 0% lỗi (0/5693), checks 11386/11386, p95 34.59 ms, p99 144.25 ms, max 491.59 ms. Cả 40 tin mention trả 201.

Đối chiếu sau khi queue về 0 (cửa sổ T0..T1, truy vấn Postgres):

| Đại lượng | Giá trị |
|---|---|
| Tin trong kênh (`messaging.messages`): của k6 / mention / tổng | 5693 / 40 / 5733 |
| Tin tạo trong lúc notification chết (05:37:10.65 tới lúc healthy 05:37:56): tổng / mention | 2153 / 23 |
| `MessageCreated` trong outbox / chưa publish | 5733 / 0 |
| Event đó có dòng `processed_event` của consumer `notification.messaging.events` | 5733 |
| `notification.mentions` của `users[49]` trong cửa sổ: số dòng / số tin khác nhau | 40 / 40 |
| `mentionCount` của `users[49]`: trước → sau | 0 → 40 |
| `channel_activity.last_message_id` so với tin mới nhất của kênh trong messaging | trùng (`01a1e803-931d-...`) |
| `notification.messaging.events.dlq` | 0 |
| Bộ đếm queue (từ lúc stack khởi động): publish / deliver / ack / redeliver | 11290 / 11290 / 11290 / 0 |
| `notification-service` sau thí nghiệm | `RestartCount=0`, `OOMKilled=false` |

Quan sát: 2153 tin (trong đó 23 tin mention) được tạo trong lúc notification chết; gửi tin không bị ảnh hưởng (0 lỗi). Message dồn trong queue tới 2014, notification lên lại thì xử lý hết phần dồn: mẫu 05:38:09 còn 147, mẫu 05:38:13 là 0, tức khoảng 17-21 s sau `Started` (05:37:52.6). Không mất: cả 5733 event của cửa sổ có dòng `processed_event`, và `mentionCount` 40 bằng đúng số tin mention đã gửi. Không nhân đôi: 40 dòng mention cho 40 tin khác nhau, `mentionCount` không vượt 40. `redeliver` 0: mẫu gần nhất trước lúc kill (05:37:10, số làm mới mỗi khoảng 5 s) là `[0,0,1]`, không có message unacked, nên lần này không kiểm được nhánh giao lại message đang xử lý dở.

## Giới hạn của các thí nghiệm

- Mỗi thí nghiệm chạy một lần (thí nghiệm 2 hai lần publish), trên một máy, một instance notification.
- Thí nghiệm 3 chạy 48 VU thay vì 50 của NFR-001, nên số độ trễ ở đây không dùng để kết luận NFR-001.
- Thí nghiệm 1 chưa giải thích được vì sao messaging còn trả 503 khoảng 16 s sau khi Community healthy, vì nguyên nhân gRPC không được log.
