# Cache quyền kênh: gửi tin có và không cache (backend-plan #4)

## Môi trường đo

- CPU: 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz
- Số core: 8 (luồng logic)
- RAM: 20164884 kB (MemTotal)
- Kernel: 7.0.0-38-generic
- Docker: 29.10.0 (build 06db173), Compose 5.7.1 (khác bản của các file đo trước vì Docker đã được cập nhật giữa hai phiên)
- Commit SHA: 63ce1d0 (HEAD lúc đo; công tắc `app.cache.permission-enabled` của Task 6 chưa commit lúc đó, image `messaging-service` build từ cây làm việc có công tắc)
- Ngày đo: 2026-11-16
- k6: `grafana/k6:2.4.0`, tag ghim trong `infra/perf/run.sh`
- Xuất OTLP: BẬT (`OTEL_ENDPOINT=http://otel-lgtm:4318`). Các lần đo của Task 1-5 chạy với OTLP TẮT, nên số ở file này không so trực tiếp với `send-message.md` được (bật OTLP thêm việc xuất metric và trace trên mọi service).
- Lệnh đã chạy (mỗi cấu hình: tạo lại `messaging-service`, `--login-only`, một lần chạy làm nóng, `--login-only`, lần chạy đo; biến `PERMISSION_CACHE_ENABLED` phải được export ở cả lệnh `--login-only` vì `setup.sh` gọi `docker compose up api-gateway` và sẽ tạo lại `messaging-service` nếu biến lệch):

```bash
export COMPOSE_PROJECT_NAME=nexus-perf POSTGRES_PORT=55432 OTEL_ENDPOINT=http://otel-lgtm:4318
export PERMISSION_CACHE_ENABLED=true   # rồi false cho cấu hình tắt cache
docker compose --profile observability up -d --build --wait messaging-service
COMPOSE_PROJECT=nexus-perf infra/perf/setup.sh --login-only
COMPOSE_PROJECT=nexus-perf infra/perf/run.sh send-message.js; echo "exit=$?"   # làm nóng, không dùng cho kết luận
COMPOSE_PROJECT=nexus-perf infra/perf/setup.sh --login-only
redis-cli --scan --pattern 'perm:*' | wc -l                                      # trong container redis, chỉ đọc
COMPOSE_PROJECT=nexus-perf infra/perf/run.sh send-message.js; echo "exit=$?"   # lần đo
infra/perf/check-memory.sh nexus-perf
```

Lần đầu của cấu hình bật cache dùng `up -d --build --wait` cho cả stack (tạo lại mọi service để nhận `OTEL_ENDPOINT`).

## Tham số

- Cùng kịch bản `send-message.js` như `send-message.md`: 50 VU, 2 phút, mỗi VU một user, 1 tin/giây, `POST /api/v1/channels/{channelId}/messages` vào kênh đã seed 1 triệu tin, rate limit api-gateway bật, mọi container dưới giới hạn bộ nhớ D1.
- Công tắc: `PERMISSION_CACHE_ENABLED=true|false` (property `app.cache.permission-enabled`, mặc định `true`). Tắt thì `channelPermissionChecker` bọc thẳng `serverChecker` (tombstone + gRPC Community), không tạo `CachingChannelPermissionChecker`, không chạm Redis. Đường kiểm tra quyền của gRPC `ChannelAccess` vốn không qua cache nên không đổi.
- Số gRPC call/s tới Community: metric server `grpc_server_milliseconds_count{service_name="community-service"}` (chỉ có series `rpc_method="CheckChannelPermissions"` trong khoảng đo), lấy từ Prometheus của `otel-lgtm` qua proxy Grafana `http://127.0.0.1:3001/api/datasources/proxy/uid/prometheus/api/v1/query` (đăng nhập `admin`/`admin` mặc định của README).
- Redis có key `perm:*` còn sống trước lần đo không: cả hai lần đo đều 0 (`redis-cli --scan --pattern 'perm:*' | wc -l` = 0 ngay trước khi chạy; không flush Redis, key hết hạn sau TTL 60 s trong lúc `--login-only`).

## Kết quả

Đơn vị ms. Số lấy từ `infra/perf/.state/send-message.summary.json` của đúng lần đo (không làm tròn). Mỗi cấu hình chỉ một lần đo nên chưa có độ phân tán giữa các lần.

| cache | p50 | p95 | p99 | req/s | gRPC call/s tới Community (`rate()` xấp xỉ; đếm chính xác 100 và 5861 call, xem dưới) | tỉ lệ hit |
|---|---|---|---|---|---|---|
| bật | 10,108049 | 41,51313250000002 | 174,62061476000022 | 48,65449304191799 | 0,8333472224537074 | 5780/5880 = 0,9829931972789115 |
| tắt | 13,032561 | 42,853453 | 222,3827205999997 | 48,438097915738624 | 49,182513624772916 | không áp dụng (cache không được tạo, không có counter) |

Chi tiết hai lần đo: bật cache 5880 request, 0 lỗi, `exit=0`, checks 11760/11760; tắt cache 5861 request, 0 lỗi, `exit=0`, checks 11722/11722; ngưỡng `p(95)<150` và `checks rate>0.99` đạt ở cả hai. max: bật 1287,122693; tắt 1154,618829.

Cách tính các cột gRPC và hit:

- PromQL: `sum(rate(grpc_server_milliseconds_count{service_name="community-service"}[2m]))`, đánh giá tại `time=` đúng lúc k6 kết thúc (bật: epoch 1794839462; tắt: epoch 1794840285). Metric OTLP của stack này được xuất mỗi 60 s (quan sát từ chuỗi giá trị: counter chỉ nhảy mỗi 60 s), nên `rate()` trên cửa sổ 2 phút chỉ có hai điểm mẫu và nội suy ra phần còn lại; số này là xấp xỉ, không phải đếm chính xác.
- Đếm chính xác bằng hiệu counter giữa hai điểm xuất bao quanh lần chạy (điểm trước lần chạy, và điểm xuất đầu tiên sau lần chạy):
  - bật: `grpc_server_milliseconds_count` 110 → 210 = 100 call; `nexus_permission_cache_misses_total` 483 → 583 = 100 miss; `nexus_permission_cache_hits_total` 5257 → 11037 = 5780 hit; 5780 + 100 = 5880 = số request của k6.
  - tắt: `grpc_server_milliseconds_count` 11799 → 17660 = 5861 call = số request của k6 (một call Community cho mỗi request). Không có series `nexus_permission_cache_*` của container mới (truy vấn trả rỗng sau khi series của container cũ hết hạn).
  - chia cho thời gian k6 (bật: 5880/48,654493 ≈ 120,85 s; tắt: 5861/48,438098 ≈ 121,0 s) ra khoảng 0,83 và 48,44 call/s.
- Tỉ lệ hit tính trên đúng 5880 request của lần đo bật: 100 miss là 50 user x 2 lần hết TTL 60 s trong 2 phút.

PromQL và kết quả thô (bật, `time=1794839462`): `sum(rate(...[2m]))` trả `"0.8333472224537074"`; `sum(increase(...[2m]))` trả `"100.0016666944449"`. Tắt (`time=1794840285`): `sum(rate(...[2m]))` trả `"49.182513624772916"`; `sum(increase(...[2m]))` trả `"5901.901634972751"`.

Tên metric đã tìm (`/api/v1/label/__name__/values`, lọc `grpc` và `permission`): `grpc_client_active_max_milliseconds`, `grpc_client_active_milliseconds_{bucket,count,sum}`, `grpc_client_max_milliseconds`, `grpc_client_milliseconds_{bucket,count,sum}`, `grpc_client_received_total`, `grpc_client_sent_total`, `grpc_server_active_max_milliseconds`, `grpc_server_active_milliseconds_{bucket,count,sum}`, `grpc_server_cancelled_total`, `grpc_server_max_milliseconds`, `grpc_server_milliseconds_{bucket,count,sum}`, `grpc_server_received_total`, `grpc_server_sent_total`, `nexus_permission_cache_hits_total`, `nexus_permission_cache_misses_total`.

## Lần chạy làm nóng và các lần chạy khác (liệt kê đủ)

Theo R12, sau mỗi lần tạo lại container có một lần chạy làm nóng cùng kịch bản; kết quả không dùng cho kết luận.

| Lần chạy | Cấu hình thật | request | p95 | lỗi | `exit` |
|---|---|---|---|---|---|
| làm nóng, bật cache | bật | 5740 | 92,64589065000003 | 383/5740 (6,67%), checks 93,32% | 99 (ngưỡng `checks` bị vượt) |
| ĐO, bật cache | bật | 5880 | 41,51313250000002 | 0 | 0 |
| làm nóng, tắt cache (lần 1) | tắt | 5729 | 100,5077124 | 0 | 0 |
| KHÔNG HỢP LỆ, định đo tắt cache | bật (xem dưới) | 5777 | 65,14122899999997 | 0 | 0 |
| làm nóng, tắt cache (lần 2) | tắt | 5759 | 99,5739243999992 | 0 | 0 |
| ĐO, tắt cache | tắt | 5861 | 42,853453 | 0 | 0 |

Lần làm nóng đầu của cấu hình bật cache có 383 request lỗi (6,67%) trên JVM lạnh; tôi không điều tra thêm ở task này (R12), dòng log k6 là `thresholds on metrics 'checks' have been crossed`. Hai lần làm nóng của cấu hình tắt cache đều không lỗi.

Lần "KHÔNG HỢP LỆ": tôi chạy `setup.sh --login-only` trong một lệnh shell không export `PERMISSION_CACHE_ENABLED=false`. `setup.sh` chạy `docker compose up api-gateway` với biến mặc định `true`, compose tạo lại `messaging-service` với cache BẬT (xác nhận bằng `docker exec ... env` cho `PERMISSION_CACHE_ENABLED=true` và uptime container mới, và bằng `nexus_permission_cache_misses_total` = 101 trong lúc lẽ ra cache tắt). Lần chạy đó thực chất là cache bật trên container vừa tạo lại, không dùng cho kết luận; giữ lại ở đây vì đã chạy và vì nó là một mẫu thứ hai của cấu hình bật (p95 65,14122899999997 so với 41,51313250000002 của lần đo bật). Sau đó tôi tạo lại `messaging-service` với `PERMISSION_CACHE_ENABLED=false` và chạy lại cả chuỗi làm nóng và đo, export biến ở mọi lệnh; `docker exec ... env` sau lần làm nóng và sau lần đo đều cho `PERMISSION_CACHE_ENABLED=false` và uptime container không đổi.

## Nhận xét

Số đo cho thấy: cache cắt số gRPC call tới Community từ 5861 (một call mỗi request, 5861 request) xuống 100 trong 2 phút (50 user x 2 lần hết TTL 60 s), tỉ lệ hit 5780/5880. Về độ trễ, chênh lệch nhỏ: p50 thấp hơn 2,924512 ms khi bật cache (10,108049 so với 13,032561), p95 gần như bằng nhau (41,51313250000002 so với 42,853453, chênh 1,340320 ms), p99 thấp hơn 47,76210584 ms khi bật (174,62061476000022 so với 222,3827205999997). Throughput gần như bằng nhau (48,65449304191799 so với 48,438097915738624 req/s) vì mỗi VU chỉ gửi 1 tin/giây nên tốc độ bị kịch bản chặn, không phải do hệ thống. Cả hai cấu hình đạt ngưỡng p95 < 150 ms với biên lớn.

Giới hạn của kết luận: mỗi cấu hình chỉ có một lần đo; `send-message.md` đã ghi hai lần chạy cùng cấu hình chênh p95 từ 24,365 đến 61,33 ms, và lần đo bật cache bị loại ở trên cho p95 65,14122899999997, lớn hơn cả lần đo tắt cache. Vì vậy chênh p95 và p99 giữa hai dòng nằm trong biên dao động giữa các lần chạy cùng cấu hình đã quan sát được, và không đủ để nói cache làm nhanh hơn ở đuôi. Chênh p50 (2,92 ms) cùng chiều với việc bỏ một lượt gRPC mỗi request, nhưng nguyên nhân chưa được kiểm chứng (chưa lặp lại, chưa đo riêng thời gian gọi Community). Lợi ích đo được chắc chắn là tải lên Community: 5861 so với 100 call trong cùng 2 phút; tác động của tải đó lên CPU hay độ trễ của Community chưa được đo.

## Bộ nhớ sau khi chạy

`infra/perf/check-memory.sh nexus-perf` ngay sau mỗi lần đo, exit 0 cả hai lần (không container nào OOMKilled hay restart). Với OTLP bật, `otel-lgtm` ở 1015 MiB (cache bật) và 1002 MiB (cache tắt) trên giới hạn 1 GiB, và `messaging-service` ở 506,3 MiB và 496,5 MiB trên giới hạn 512 MiB: cả hai sát giới hạn.

Cache bật (sau lần đo bật):

```text
container                                       usage        limit
nexus-perf-api-gateway-1                     328.6MiB       512MiB
nexus-perf-notification-service-1            382.3MiB       512MiB
nexus-perf-realtime-gateway-2                233.6MiB       512MiB
nexus-perf-presence-service-1                275.6MiB       512MiB
nexus-perf-realtime-gateway-1                241.6MiB       512MiB
nexus-perf-identity-service-1                  387MiB       512MiB
nexus-perf-community-service-1               425.8MiB       512MiB
nexus-perf-messaging-service-1               506.3MiB       512MiB
nexus-perf-postgres-1                        156.2MiB       768MiB
nexus-perf-rabbitmq-1                        133.7MiB       512MiB
nexus-perf-redis-1                           5.293MiB       128MiB
nexus-perf-otel-lgtm-1                        1015MiB         1GiB
TOTAL 4.00 GiB
```

Cache tắt (sau lần đo tắt):

```text
container                                       usage        limit
nexus-perf-api-gateway-1                     327.9MiB       512MiB
nexus-perf-messaging-service-1               496.5MiB       512MiB
nexus-perf-notification-service-1            400.2MiB       512MiB
nexus-perf-realtime-gateway-2                251.4MiB       512MiB
nexus-perf-presence-service-1                293.8MiB       512MiB
nexus-perf-realtime-gateway-1                258.7MiB       512MiB
nexus-perf-identity-service-1                406.6MiB       512MiB
nexus-perf-community-service-1               450.6MiB       512MiB
nexus-perf-postgres-1                        188.6MiB       768MiB
nexus-perf-rabbitmq-1                        135.2MiB       512MiB
nexus-perf-redis-1                           5.539MiB       128MiB
nexus-perf-otel-lgtm-1                        1002MiB         1GiB
TOTAL 4.12 GiB
```

Sau khi đo, `messaging-service` được tạo lại với `PERMISSION_CACHE_ENABLED` không đặt; `docker exec ... env` cho `PERMISSION_CACHE_ENABLED=true`.
