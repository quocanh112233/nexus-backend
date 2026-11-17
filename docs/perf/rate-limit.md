# Rate limit gửi tin: trả 429 khi vượt ngưỡng (NFR-013)

## Môi trường đo

- CPU: 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz
- Số core: 8 (luồng logic)
- RAM: 20164884 kB (MemTotal)
- Kernel: 7.0.0-38-generic
- Docker: 29.10.0 (build 06db173), Compose 5.7.1
- Commit SHA: 72e2f33 (HEAD lúc đo; bản sửa thứ tự của `rate-limit.js` chưa commit lúc đó)
- Ngày đo: 2026-11-16
- k6: `grafana/k6:2.4.0`, tag ghim trong `infra/perf/run.sh`
- Xuất OTLP: BẬT (`OTEL_ENDPOINT=http://otel-lgtm:4318`). Các lần đo của Task 1-5 chạy với OTLP TẮT.
- Lệnh đã chạy:

```bash
export COMPOSE_PROJECT_NAME=nexus-perf POSTGRES_PORT=55432 OTEL_ENDPOINT=http://otel-lgtm:4318
COMPOSE_PROJECT=nexus-perf infra/perf/setup.sh --login-only
COMPOSE_PROJECT=nexus-perf infra/perf/run.sh rate-limit.js; echo "exit=$?"   # lần chạy làm nóng (thứ tự mới)
sleep 20
COMPOSE_PROJECT=nexus-perf infra/perf/run.sh rate-limit.js; echo "exit=$?"   # lần chạy đo
```

## Tham số

- Route `messaging-send` của api-gateway (`gateways/api-gateway/src/main/resources/application.yml`): `burstCapacity: 5`, `replenishRate: 1` (1 token/giây), `requestedTokens: 1`, khóa theo `sub` của token, `throw-on-limit: true`. Rate limit bật (`RATELIMIT_ENABLED=true`).
- Kịch bản (`infra/perf/k6/rate-limit.js`, 1 VU, 1 lần lặp): `users[10]` gửi 20 tin liên tiếp không `sleep`; `users[11]` gửi 1 tin ngay sau đó (lúc bucket của `users[10]` đang cạn); `sleep(6)`; `users[10]` gửi thêm 1 tin.
- Ngưỡng: `accepted` `count>=5` và `count<=8`, `limited` `count>=12`, `checks` `rate==1`. Mỗi 429 phải có header `Retry-After`; tin của `users[11]` (gửi ngay sau 20 tin, trước `sleep(6)`) và tin của `users[10]` sau 6 giây phải là 201.
- Mã khác 201 và 429 được đếm riêng bằng `other` (chỉ in ra khi có ít nhất một lần).

## Kết quả

Mỗi bộ chạy gồm `setup.sh --login-only` (tạo lại `api-gateway` hai lần nên gateway nguội), một lần làm nóng (không dùng cho kết luận), chờ 20 giây để bucket nạp đầy, một lần đo.

Bộ chạy cuối (thứ tự mới: `users[11]` gửi ngay sau 20 tin, trước `sleep(6)`), dùng cho kết luận:

| Lần chạy | exit | 201 (accepted, 20 tin đầu) | 429 (limited) | khác 201/429 | `users[11]` | check | Retry-After quan sát được |
|---|---|---|---|---|---|---|---|
| Làm nóng | 0 | 6 | 14 | 0 | 201 | 16/16 | `1` ở cả 14 lần 429 |
| Đo | 0 | 6 | 14 | 0 | 201 | 16/16 | `1` ở cả 14 lần 429 |

Lần đo: 22 request (20 tin liên tiếp của `users[10]`, 1 tin của `users[11]`, 1 tin của `users[10]` sau 6 giây), cả hai tin sau đều 201. Cả ba ngưỡng đạt (`accepted` count=6, `limited` count=14, `checks` rate=100%). Không có mã khác 201/429 (metric `other` không phát sinh). Giá trị `Retry-After` ghi đúng như gateway trả về, không quy đổi đơn vị.

Bằng chứng `users[10]` đang bị giới hạn lúc `users[11]` gửi: trong lần đo, lần gửi thứ 20 (i=19, cuối vòng lặp) của `users[10]` trả 429 lúc 15:05:14Z, và dòng `users[11] status=201` ghi ngay cùng giây 15:05:14Z, không có `sleep` giữa hai lần gửi.

Bộ chạy trước (thứ tự cũ: `sleep(6)` rồi `users[11]` gửi sau tin 201 của `users[10]`) đã bị thay thế vì check `users[11]` không phân biệt được: sau 6 giây một bucket dùng chung cũng đã nạp lại khoảng 6 token nên vẫn trả 201. Hai lần đó đều exit 0: làm nóng 201=7, 429=13; đo 201=6, 429=14; khác 201/429 = 0; Retry-After `1`. Số 201/429 của 20 tin đầu không phụ thuộc thứ tự mới nên khớp bộ cuối.

Lần làm nóng bộ trước nhận 7 tin 201 trong 20 tin liên tiếp, nhiều hơn burst 5, vì bucket nạp thêm token trong lúc gửi 20 request.

## Kết luận NFR-013

ĐẠT cho đường gửi tin `POST /api/v1/channels/{id}/messages` (route `messaging-send`): vượt ngưỡng thì gateway trả 429 kèm `Retry-After: 1`, và 6 giây sau bucket của user đó nạp lại và gửi được. Lúc `users[10]` đang bị 429, `users[11]` vẫn gửi được 201, nên bucket không dùng chung giữa hai user này. Phép đo không tự chứng minh khóa là `sub` (cấu hình khai báo `subKeyResolver`), chỉ cho thấy khóa khác nhau giữa hai user. Kết luận chỉ áp dụng cho route này; các route khác (auth, community, DM...) không được đo ở đây.
