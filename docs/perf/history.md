# Phân trang lịch sử: keyset và OFFSET trên 1 triệu tin (NFR-003)

## Môi trường đo

- CPU: 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz
- Số core: 8 (luồng logic)
- RAM: 20164884 kB (MemTotal)
- Kernel: 7.0.0-38-generic
- Docker: 29.8.2 (build 7fc2dff), Compose 5.6.0
- Commit SHA: lần đo bảng một kênh be18d73, lần đo bảng nhiều kênh bc4efd3 (HEAD lúc đo; thay đổi `VACUUM FULL` trong script seed và tài liệu này nằm ở commit sau đó)
- Ngày đo: 2026-11-15 (bảng một kênh), 2026-11-16 (bảng nhiều kênh)
- Lệnh đã chạy:

```bash
export COMPOSE_PROJECT_NAME=nexus-perf POSTGRES_PORT=55432
docker compose --profile observability up -d --build --wait
COMPOSE_PROJECT=nexus-perf infra/perf/setup.sh
time COMPOSE_PROJECT=nexus-perf infra/perf/seed-messages.sh
time RESET=1 COMPOSE_PROJECT=nexus-perf infra/perf/seed-messages.sh
docker compose -p nexus-perf exec -T postgres psql -U postgres -d nexus -v channel=<channelId> -f - < infra/perf/explain-history.sql
```

PostgreSQL 16.15, `shared_buffers` 128MB, container `postgres` giới hạn 768m.

## Seed

Một transaction chạy hai câu `INSERT ... SELECT generate_series` rồi `ANALYZE messaging.messages`: 1 000 000 tin vào kênh đo, cộng (mặc định `NOISE_CHANNELS=20` x `NOISE_PER_CHANNEL=50000`) 1 000 000 tin nhiễu ở 20 kênh ngẫu nhiên. Tin nhiễu cùng tác giả, `content = 'noise N'`, `created_at` rải đều trên cùng khoảng thời gian với kênh đo nên id đan xen theo thời gian (vị trí trong heap thì không đan xen, xem phần hạn chế bên dưới); `NOISE_CHANNELS=0` bỏ nhiễu. `RESET=1` xóa tin cũ rồi chạy `VACUUM FULL messaging.messages` để bảng và index không còn chỗ chết của các tin đã xóa.

- Lần seed chỉ có kênh đo (2026-11-15): phần chèn và ANALYZE mất 8,922 s, cả script 9,343 s.
- Lần seed có nhiễu (2026-11-16, chạy với `RESET=1`: xóa 2 000 000 tin cũ, `VACUUM FULL`, rồi chèn lại): phần chèn 2 triệu tin và ANALYZE mất 18,744 s, cả script kể cả xóa, `VACUUM FULL`, đếm và ghi state mất 1 m 27,126 s. Cả hai đều dưới 5 phút.
- Sau lần có nhiễu: kênh đo 1 000 000 tin, nhiễu 1 000 000 tin ở 20 kênh, bảng tổng 2 000 003 tin (3 tin còn lại là của demo); thứ tự id trùng thứ tự `created_at` ở kênh đo (0 cặp ngược).

Ghi chú D4: seed ghi thẳng vào `messaging.messages`, bỏ qua outbox. Không có event nào được phát, nên notification-service và các consumer khác không biết các tin này. Chấp nhận được vì bài đo chỉ xét truy vấn đọc lịch sử.

## Bảng chỉ có một kênh

Đo ngày 2026-11-15 trên commit be18d73, bảng `messaging.messages` có 1 000 003 tin, trong đó 1 000 000 thuộc kênh đo (3 tin còn lại là của demo).

Mỗi câu chạy 3 lần liên tiếp, lấy lần thứ ba (cache ấm), `LIMIT 50`. Độ sâu tính theo số tin mới hơn trang đang lấy. Keyset dùng đúng câu của `MessageDao.before` với con trỏ là id ở vị trí tương ứng tính từ mới nhất; ở độ sâu 999 950 chỉ còn 49 tin cũ hơn con trỏ nên trả 49 hàng.

| Cách | Độ sâu | Execution Time (ms) | Buffers shared hit/read | Node chính của plan |
|---|---|---|---|---|
| keyset | 0 | 0,025 | 5/0 | Index Scan Backward using messages_pkey |
| keyset | 100 000 | 0,030 | 5/0 | Index Scan Backward using messages_pkey |
| keyset | 500 000 | 0,032 | 4/0 | Index Scan Backward using messages_pkey |
| keyset | 999 950 | 0,025 | 5/0 | Index Scan Backward using messages_pkey |
| OFFSET | 0 | 0,026 | 5/0 | Index Scan Backward using messages_pkey |
| OFFSET | 100 000 | 13,493 | 2205/0 | Index Scan Backward using messages_pkey |
| OFFSET | 500 000 | 70,112 | 10985/0 | Index Scan Backward using messages_pkey |
| OFFSET | 999 950 | 144,606 | 18083/3874 | Index Scan Backward using messages_pkey |

Keyset gần như không đổi theo độ sâu (0,025 đến 0,032 ms, 4 đến 5 buffer). OFFSET tăng gần tuyến tính theo độ sâu, từ 0,026 ms lên 144,606 ms ở độ sâu 999 950, chậm hơn keyset khoảng 5800 lần.

### Vì sao OFFSET chậm dần

`OFFSET n` không nhảy tới hàng thứ n. Postgres vẫn phải đọc và loại bỏ n hàng đầu rồi mới trả 50 hàng kế tiếp, nên node Index Scan ở plan OFFSET đọc `rows=100050`, `500050`, `1000000` và số buffer tăng tương ứng, trong khi `LIMIT` chỉ giữ 50 hàng. Keyset đặt điều kiện `id < :before` vào `Index Cond`, index đi thẳng tới vị trí con trỏ rồi đọc đúng 50 hàng, chi phí không phụ thuộc độ sâu.

### Plan không dùng `messages_channel_id_desc`

Mọi plan, cả keyset lẫn OFFSET, chọn `messages_pkey` (quét ngược theo `id`, `channel_id` là `Filter`) thay vì `messages_channel_id_desc` như dự kiến. Lý do thấy được trong dữ liệu đo: kênh seed chiếm 1 000 000 trong 1 000 003 tin của bảng, `channel_id` gần như không lọc được gì, và pkey (30 MB) nhỏ hơn hẳn index `(channel_id, id DESC)` (85 MB) nên planner coi quét pkey rẻ hơn. Bảng một kênh không phải hình dạng mà NFR-003 nhắm tới (ruling R7), nên phần dưới đo lại trên bảng nhiều kênh; D8 vẫn giữ: không đổi index, query hay cấu hình.

Ở lần thứ ba của OFFSET 999 950 vẫn còn `read=3874` do quét hết 1 triệu hàng vượt `shared_buffers` 128MB nên một phần trang bị đẩy ra; con số 144,606 ms phản ánh điều đó.

### Plan nguyên văn của hai câu sâu nhất (bảng một kênh)

Keyset, độ sâu 999 950:

```text
                                                                   QUERY PLAN                                                                    
-------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=0.42..2.97 rows=50 width=253) (actual time=0.008..0.015 rows=49 loops=1)
   Buffers: shared hit=5
   ->  Index Scan Backward using messages_pkey on messages m  (cost=0.42..255.43 rows=5000 width=253) (actual time=0.008..0.013 rows=49 loops=1)
         Index Cond: (id < '01a1a241-013b-7761-86d9-9e3f3ead018a'::uuid)
         Filter: (channel_id = '01a1ddd3-2d41-75e5-aef9-dee44a7f9826'::uuid)
         Buffers: shared hit=5
 Planning:
   Buffers: shared hit=4
 Planning Time: 0.049 ms
 Execution Time: 0.025 ms
(10 rows)
```

OFFSET, độ sâu 999 950:

```text
                                                                         QUERY PLAN                                                                          
-------------------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=47198.98..47201.34 rows=50 width=253) (actual time=144.374..144.387 rows=50 loops=1)
   Buffers: shared hit=18083 read=3874 written=1
   ->  Index Scan Backward using messages_pkey on messages m  (cost=0.42..47201.48 rows=1000003 width=253) (actual time=0.019..119.879 rows=1000000 loops=1)
         Filter: (channel_id = '01a1ddd3-2d41-75e5-aef9-dee44a7f9826'::uuid)
         Rows Removed by Filter: 3
         Buffers: shared hit=18083 read=3874 written=1
 Planning Time: 0.061 ms
 Execution Time: 144.606 ms
(8 rows)
```

## Bảng nhiều kênh (1 triệu tin kênh đo + 1 triệu tin nhiễu ở 20 kênh)

Đo ngày 2026-11-16 trên cùng stack, sau khi `RESET=1` xóa, `VACUUM FULL` rồi seed lại có nhiễu (2 000 003 tin). Cách đo như phần trên: 3 lần liên tiếp lấy lần thứ ba, `LIMIT 50`; ở độ sâu 999 950 chỉ còn 49 tin cũ hơn con trỏ nên keyset trả 49 hàng.

Kích thước sau seed (`pg_relation_size`, `relpages`): `messages` 234 061 824 byte (223 MB, 28 572 trang), `messages_pkey` 63 102 976 byte (60 MB, 7 703 trang), `messages_channel_id_desc` 177 987 584 byte (170 MB, 21 727 trang). `shared_buffers` 128MB tương đương 16 384 trang 8 kB.

Vị trí heap theo `ctid`: kênh đo nằm ở các block 0..14285 (14 286 block khác nhau, kèm 3 tin demo ở block 0), tin nhiễu nằm ở các block 14285..28571 (14 287 block khác nhau).

| Cách | Độ sâu | Execution Time (ms) | Buffers shared hit/read | Node chính của plan |
|---|---|---|---|---|
| keyset | 0 | 0,024 | 6/0 | Index Scan using messages_channel_id_desc |
| keyset | 100 000 | 0,027 | 7/0 | Index Scan using messages_channel_id_desc |
| keyset | 500 000 | 0,032 | 6/0 | Index Scan using messages_channel_id_desc |
| keyset | 999 950 | 0,028 | 6/0 | Index Scan using messages_channel_id_desc |
| OFFSET | 0 | 0,029 | 6/0 | Index Scan using messages_channel_id_desc |
| OFFSET | 100 000 | 13,993 | 2509/0 | Index Scan using messages_channel_id_desc |
| OFFSET | 500 000 | 56,946 | 12524/0 | Index Scan using messages_channel_id_desc |
| OFFSET | 999 950 | 167,601 | 0/25042 | Index Scan using messages_channel_id_desc |

Cả 8 câu đều dùng `messages_channel_id_desc`; keyset đặt cả `channel_id` và `id < :before` vào `Index Cond`, OFFSET chỉ có `channel_id` trong `Index Cond`. Plan OFFSET 999 950 có thêm khối `JIT`, không thuộc node đọc dữ liệu.

Những gì số đo hỗ trợ:

- Keyset không phụ thuộc độ sâu: 0,024 đến 0,032 ms, 6 đến 7 buffer, toàn bộ là `hit`.
- OFFSET tăng theo độ sâu vì node Index Scan phải đọc `rows=100050`, `500050`, `1000000` rồi mới bỏ đi n hàng đầu; số buffer tăng tương ứng (2509, 12524, 25042) và thời gian từ 0,029 ms lên 167,601 ms.
- Ở độ sâu 999 950 lần thứ ba vẫn `read=25042`, `hit=0`. Số trang này lớn hơn 16 384 trang của `shared_buffers`, nên các trang không còn nằm trong cache giữa hai lần chạy; phù hợp với việc 167,601 ms có I/O, nhưng chưa tách riêng phần đọc từ đĩa và từ cache của hệ điều hành nên mức độ ảnh hưởng của I/O là chưa xác định. Con số này vì vậy không phải số đo cache ấm hoàn toàn.
- Con số 25 042 gần với 14 286 block heap của kênh đo cộng một nửa số trang của index `messages_channel_id_desc` (khoảng 10 864 trang, vì kênh đo chiếm một nửa số dòng); đây là phép cộng khớp số, chưa kiểm riêng từng loại trang.

Hạn chế của dữ liệu đo: kênh đo và tin nhiễu được chèn bằng hai câu `INSERT` nên chiếm hai dải block heap liên tiếp (0..14285 và 14285..28571), heap không đan xen theo kênh như một bảng đang chạy thật. Chỉ id và `created_at` là đan xen. Cách các trang heap bị đọc ở bảng thật (rải đều khắp heap) có thể khác và không được đo ở đây.

Lần đo trước trong cùng ngày (`RESET=1` chưa có `VACUUM FULL`) bị loại: bảng còn khoảng 112 MB chỗ chết của 1 triệu tin đã xóa (`messages` 335 MB, `messages_pkey` 90 MB, `messages_channel_id_desc` 255 MB) và cho OFFSET 500 000 / 999 950 là 866,469 / 1403,937 ms. Các số đó không dùng; bảng trên là số đo sau `VACUUM FULL`.

### Plan nguyên văn của hai câu sâu nhất (bảng nhiều kênh)

Keyset, độ sâu 999 950:

```text
                                                                     QUERY PLAN                                                                      
-----------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=0.55..105.91 rows=50 width=253) (actual time=0.010..0.017 rows=49 loops=1)
   Buffers: shared hit=6
   ->  Index Scan using messages_channel_id_desc on messages m  (cost=0.55..10683.53 rows=5070 width=253) (actual time=0.010..0.014 rows=49 loops=1)
         Index Cond: ((channel_id = '01a1ddd3-2d41-75e5-aef9-dee44a7f9826'::uuid) AND (id < '01a1a7a3-df88-7d87-8438-271c18d65ee9'::uuid))
         Buffers: shared hit=6
 Planning:
   Buffers: shared hit=4
 Planning Time: 0.070 ms
 Execution Time: 0.028 ms
(9 rows)
```

OFFSET, độ sâu 999 950:

```text
                                                                           QUERY PLAN                                                                           
----------------------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=129784.16..129790.65 rows=50 width=253) (actual time=167.376..167.386 rows=50 loops=1)
   Buffers: shared read=25042
   ->  Index Scan using messages_channel_id_desc on messages m  (cost=0.55..131607.97 rows=1014002 width=253) (actual time=0.029..142.627 rows=1000000 loops=1)
         Index Cond: (channel_id = '01a1ddd3-2d41-75e5-aef9-dee44a7f9826'::uuid)
         Buffers: shared read=25042
 Planning Time: 0.059 ms
 JIT:
   Functions: 4
   Options: Inlining false, Optimization false, Expressions true, Deforming true
   Timing: Generation 0.168 ms, Inlining 0.000 ms, Optimization 0.089 ms, Emission 1.448 ms, Total 1.705 ms
 Execution Time: 167.601 ms
(11 rows)
```

## Kết luận NFR-003

Trên bảng nhiều kênh, phân trang keyset đi bằng `messages_channel_id_desc` và tốn 0,024 đến 0,032 ms với 6 đến 7 buffer ở mọi độ sâu từ 0 đến 999 950, tức không phụ thuộc độ sâu. Cùng dữ liệu, OFFSET tăng từ 0,029 ms lên 167,601 ms ở độ sâu 999 950, chậm hơn keyset khoảng 6000 lần. Khi một trang là 50 tin, keyset phục vụ được lịch sử 1 triệu tin của một kênh trong hàng chục micro giây mỗi trang, nên truy vấn DB không phải nút cổ chai so với ngưỡng p95 < 50 ms của NFR-003 (một lần `EXPLAIN ANALYZE` trên bảng đã nóng, không gồm độ trễ mạng và service), với hạn chế về bố cục heap nêu trên. Đây là số đo thuần truy vấn DB, chưa phải kết luận NFR-003; kết luận nằm ở phần k6 p95 qua API bên dưới.

## Qua API (k6)

Đo ngày 2026-11-16 trên commit c4198ac (bản `history.js` có nhịp độ chưa commit lúc đó), k6 `grafana/k6:2.4.0` chạy trong network của stack, gọi `GET /api/v1/channels/{channelId}/messages` qua `http://api-gateway:8080` vào kênh 1 triệu tin (kèm khoảng 11 700 tin do bài đo gửi tin thêm).

```bash
export COMPOSE_PROJECT_NAME=nexus-perf POSTGRES_PORT=55432
COMPOSE_PROJECT=nexus-perf infra/perf/setup.sh --login-only
COMPOSE_PROJECT=nexus-perf infra/perf/run.sh history.js; echo "exit=$?"
infra/perf/check-memory.sh nexus-perf
```

Tham số: 50 VU (`constant-vus`), 1 phút, mỗi VU dùng token của một user riêng. Mỗi vòng lặp: trang mới nhất (`?limit=50`), `sleep(1)`, trang sâu (`?limit=50&before=<midMessageId>`, trước tin thứ 500 000), `sleep(1)`; cả hai request cùng tag `name: history`. Mỗi user tối đa 60 request mỗi phút, dưới bucket rate limit 100 request/phút; rate limit và cache quyền bật. Mức tải là 50 VU, khoảng 50 request/giây (48,44 req/s đo được); đây là mức tải của bài đo, không phải trần thông lượng của hệ thống. Ngưỡng: `http_req_duration{name:history}` `p(95)<50` và `checks` `rate>0.99`; mỗi request kiểm mã `200` và mảng đúng 50 tin của đúng kênh.

Số lấy từ `infra/perf/.state/history.summary.json` (3000 request, 6000 check đều đạt, 0 request lỗi, 0 mã 429, k6 thoát `exit=0`). Đơn vị ms.

| p50 | p90 | p95 | p99 | max | req/s | tỉ lệ lỗi | số 429 |
|---|---|---|---|---|---|---|---|
| 9,320 | 16,371 | 26,854 | 973,258 | 1087,595 | 48,440 | 0% (0/3000) | 0 |

Kết luận NFR-003 qua API: ĐẠT. p95 26,854 ms, dưới ngưỡng 50 ms trên kênh 1 triệu tin, ở cả trang mới nhất lẫn trang sâu (hai loại request gộp trong cùng tag `history`, chưa tách riêng từng loại). Từ p99 973,258 ms suy ra khoảng 1% request (khoảng 30 trong 3000) mất từ khoảng 973 ms trở lên, chậm nhất 1087,595 ms (chỉ giá trị max vượt 1 s); chưa điều tra nguyên nhân (D8: P9 chỉ đo).

Lần thử đầu (20 VU, không `sleep`) bị loại: 95,33% request bị rate limit từ chối nên `checks` chỉ đạt 4,66%, phép đo không hợp lệ và độ trễ của lần đó không được dùng làm kết quả. Bài đo ở trên là lần chạy có nhịp độ dưới bucket rate limit.

Bộ nhớ sau lần chạy (`check-memory.sh`, exit 0, không container nào OOMKilled hay restart): TOTAL 3.73 GiB, `messaging-service` 487,7 MiB trên giới hạn 512 MiB.
