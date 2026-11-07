#!/bin/bash
set -euo pipefail
# Demo P4: A và B nhắn tin trong kênh mặc định (nonce, reply, reaction, sửa, xóa, phân trang), C ngoài server bị 404; tùy chọn dừng community-service để xem 503 nhanh
# Gọi qua api-gateway :8080 (đổi bằng BASE_URL); mỗi lần chạy tốn 6 request /auth/** (3 user x đăng ký + đăng nhập),
# gateway giới hạn ~10/phút/IP nên chạy lần 2 trong vòng 1 phút sẽ bị 429 (chờ 1 phút rồi chạy lại)
# Phần 503 chỉ chạy khi DEMO_STOP_COMMUNITY=1 và phải đặt rõ COMPOSE_PROJECT (tên project compose đang chạy stack), vì script chạy
# xóa key `perm:*` trong redis và dừng community-service của project đó; không đặt thì phần này bị bỏ qua để an toàn
# In X-Request-Id của từng bước để tra log trong Grafana/Loki; không in token hay mật khẩu
# Cần curl và jq trên máy chạy script
for tool in curl jq; do
  command -v "$tool" >/dev/null || { echo "FAIL: thiếu $tool, cài $tool rồi chạy lại"; exit 1; }
done
BASE="${BASE_URL:-http://127.0.0.1:8080}"
SUFFIX="$(date +%s)$RANDOM"
PASSWORD="demo-pass-123"
HDR="$(mktemp)"
STOPPED=0
# Dọn file header tạm và bật lại community-service nếu script dừng giữa phần 503
trap 'rm -f "$HDR"; [ "$STOPPED" = 1 ] && dc start community-service >/dev/null 2>&1 || true' EXIT
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# Gọi API, ghi header vào $HDR, in "<body> <status>" ra stdout; $1 method, $2 path, còn lại là tham số curl
call() {
  local method="$1" path="$2"
  shift 2
  curl -sS -X "$method" "$BASE$path" -D "$HDR" -w ' %{http_code}' "$@"
}
# Kiểm tra mã HTTP cuối chuỗi $1 bằng $2, in bước $3 kèm X-Request-Id, thoát 1 nếu sai
expect() {
  local out="$1" want="$2" step="$3" code rid
  code="${out##* }"
  rid="$(tr -d '\r' <"$HDR" | awk -F': ' 'tolower($1) == "x-request-id" { print $2 }')"
  echo "$step -> HTTP $code requestId=$rid"
  [ "$code" = "429" ] && echo "Gateway giới hạn: chờ 1 phút rồi chạy lại"
  [ "$code" = "$want" ] || { echo "FAIL: $step cần HTTP $want"; exit 1; }
}
# Kiểm tra biểu thức jq $2 đúng trên body của $1, thoát 1 nếu sai
check() {
  echo "${1% *}" | jq -e "$2" >/dev/null || { echo "FAIL: $3"; exit 1; }
}
# Đăng ký, đăng nhập user có tiền tố $1; token vào TOKEN_$1 (không in ra), userId vào ID_$1
signup() {
  local who="$1" out token
  out="$(call POST /api/v1/auth/register -H 'Content-Type: application/json' \
    -d "{\"email\":\"$who$SUFFIX@example.com\",\"username\":\"$who$SUFFIX\",\"password\":\"$PASSWORD\"}")"
  expect "$out" 201 "register $who"
  out="$(call POST /api/v1/auth/login -H 'Content-Type: application/json' \
    -d "{\"email\":\"$who$SUFFIX@example.com\",\"password\":\"$PASSWORD\"}")"
  expect "$out" 200 "login $who"
  token="$(echo "${out% *}" | jq -er .accessToken)" || token=""
  [ -n "$token" ] || { echo "FAIL: login $who không trả accessToken"; exit 1; }
  printf -v "TOKEN_$who" '%s' "$token"
}
# Header Authorization cho user $1
auth() {
  local var="TOKEN_$1"
  echo "Authorization: Bearer ${!var}"
}
# Gửi tin của user $1 vào kênh mặc định với body JSON $2; in "<body> <status> <time_total>" (time_total là giây, -w sau ghi đè -w của call)
send() {
  call POST "$M" -H "$(auth "$1")" -H 'Content-Type: application/json' -d "$2" -w ' %{http_code} %{time_total}'
}

signup a
signup b
signup c

out="$(call POST /api/v1/servers -H "$(auth a)" -H 'Content-Type: application/json' \
  -d "{\"name\":\"demo-$SUFFIX\"}")"
expect "$out" 201 "1. A tạo server"
SERVER_ID="$(echo "${out% *}" | jq -er .id)"
CHANNEL_ID="$(echo "${out% *}" | jq -er '.channels[0].id')"
M="/api/v1/channels/$CHANNEL_ID/messages"

out="$(call POST "/api/v1/servers/$SERVER_ID/invites" -H "$(auth a)" -H 'Content-Type: application/json' -d '{}')"
expect "$out" 201 "2. A tạo invite"
CODE="$(echo "${out% *}" | jq -er .code)"
out="$(call POST "/api/v1/invites/$CODE/accept" -H "$(auth b)")"
expect "$out" 200 "3. B tham gia bằng invite"

NONCE="n-$SUFFIX"
out="$(call POST "$M" -H "$(auth a)" -H 'Content-Type: application/json' \
  -d "{\"content\":\"xin chào\",\"nonce\":\"$NONCE\"}")"
expect "$out" 201 "4. A gửi tin kèm nonce"
MSG1="$(echo "${out% *}" | jq -er .id)"

out="$(call POST "$M" -H "$(auth a)" -H 'Content-Type: application/json' \
  -d "{\"content\":\"xin chào\",\"nonce\":\"$NONCE\"}")"
expect "$out" 200 "5. A gửi lại cùng nonce"
check "$out" ".id == \"$MSG1\"" "gửi lại cùng nonce phải trả cùng id"

out="$(call POST "$M" -H "$(auth b)" -H 'Content-Type: application/json' \
  -d "{\"content\":\"chào A\",\"replyToId\":\"$MSG1\"}")"
expect "$out" 201 "6. B trả lời tin của A"
MSG2="$(echo "${out% *}" | jq -er .id)"
check "$out" ".replyToId == \"$MSG1\"" "tin reply phải mang replyToId"

# 👍 mã hóa URL
out="$(call PUT "$M/$MSG1/reactions/%F0%9F%91%8D" -H "$(auth b)")"
expect "$out" 204 "7. B thả reaction 👍 vào tin của A"
out="$(call GET "$M" -H "$(auth b)")"
expect "$out" 200 "8. B xem lịch sử"
check "$out" ".[] | select(.id == \"$MSG1\") | .reactions | length == 1 and .[0].emoji == \"👍\" and .[0].count == 1 and .[0].me == true" \
  "lịch sử phải có reaction 👍 count=1 me=true trên tin đầu"

out="$(call PATCH "$M/$MSG1" -H "$(auth a)" -H 'Content-Type: application/json' -d '{"content":"xin chào (đã sửa)"}')"
expect "$out" 200 "9. A sửa tin"
check "$out" '.editedAt != null and .content == "xin chào (đã sửa)"' "tin sau khi sửa phải có editedAt và nội dung mới"

out="$(call POST "$M" -H "$(auth a)" -H 'Content-Type: application/json' -d '{"content":"tin thứ ba"}')"
expect "$out" 201 "9b. A gửi thêm tin thứ ba để phân trang after có từ 2 tin trở lên"

out="$(call GET "$M?after=$MSG1" -H "$(auth b)")"
expect "$out" 200 "10. B lấy tin sau tin đầu (after)"
check "$out" "length >= 2 and .[0].id == \"$MSG2\"" "after=<tin đầu> phải trả từ 2 tin, tin reply đầu tiên"
check "$out" 'map(.createdAt) as $t | $t == ($t | sort)' "after phải trả tăng dần theo thời gian"

out="$(call POST "$M" -H "$(auth c)" -H 'Content-Type: application/json' -d '{"content":"lén vào"}')"
expect "$out" 404 "11. C (ngoài server) gửi tin"

out="$(call DELETE "$M/$MSG1" -H "$(auth a)")"
expect "$out" 204 "12. A xóa tin"
out="$(call GET "$M" -H "$(auth b)")"
expect "$out" 200 "13. B xem lịch sử sau khi xóa"
check "$out" ".[] | select(.id == \"$MSG1\") | .deleted == true and .content == null" "tin đã xóa phải là tombstone (deleted=true, content=null)"
echo "OK: 13 bước nhắn tin, serverId=$SERVER_ID channelId=$CHANNEL_ID"

if [ "${DEMO_STOP_COMMUNITY:-0}" != "1" ]; then
  echo "Bỏ qua phần 503 (đặt DEMO_STOP_COMMUNITY=1 và COMPOSE_PROJECT=<project> để chạy; script sẽ xóa key perm:* trong redis và dừng community-service của project đó)"
  exit 0
fi
PROJECT="${COMPOSE_PROJECT:-}"
[ -n "$PROJECT" ] || { echo "FAIL: phải đặt COMPOSE_PROJECT khi DEMO_STOP_COMMUNITY=1"; exit 1; }
# Chạy docker compose của project $COMPOSE_PROJECT từ thư mục gốc repo
dc() { (cd "$ROOT" && docker compose -p "$PROJECT" "$@"); }

# Community đã healthy khi dc ps in "(healthy)" (không khớp "unhealthy")
is_healthy() {
  local status
  status="$(dc ps community-service)"
  grep -q '(healthy)' <<<"$status"
}

# Xóa cache quyền để lần gửi sau buộc gọi gRPC tới Community
dc exec -T redis sh -c "redis-cli --scan --pattern 'perm:*' | xargs -r redis-cli del"
STOPPED=1
dc stop community-service
out="$(send a '{"content":"khi community tắt"}')"
code="$(echo "$out" | awk '{print $(NF-1)}')"
secs="$(echo "$out" | awk '{print $NF}')"
body="${out% * *}"
rid="$(tr -d '\r' <"$HDR" | awk -F': ' 'tolower($1) == "x-request-id" { print $2 }')"
echo "14. A gửi tin khi Community tắt -> HTTP $code time_total=${secs}s requestId=$rid"
[ "$code" = "503" ] || { echo "FAIL: cần HTTP 503"; exit 1; }
echo "$body" | jq -e '.code == "DEPENDENCY_UNAVAILABLE"' >/dev/null || { echo "FAIL: code phải là DEPENDENCY_UNAVAILABLE"; exit 1; }
awk -v t="$secs" 'BEGIN { exit !(t < 1) }' || { echo "FAIL: 503 phải trả dưới 1 s"; exit 1; }

n503=0
for i in $(seq 1 20); do
  out="$(send a "{\"content\":\"tắt $i\"}")"
  code="$(echo "$out" | awk '{print $(NF-1)}')"
  secs="$(echo "$out" | awk '{print $NF}')"
  echo "   lần $i: HTTP $code time_total=${secs}s"
  [ "$code" = "503" ] && n503=$((n503 + 1))
  sleep 1.1 # tránh rate limit gửi tin của gateway (5 tin burst, 1 tin/giây)
done
echo "15. $n503/20 lần trả 503"

dc start community-service
STOPPED=0
for _ in $(seq 1 60); do
  is_healthy && break
  sleep 2
done
is_healthy || { echo "FAIL: community-service không healthy sau 120 s"; exit 1; }
sleep 12 # breaker mở 10 s rồi nửa mở
out="$(send a '{"content":"community đã chạy lại"}')"
code="$(echo "$out" | awk '{print $(NF-1)}')"
secs="$(echo "$out" | awk '{print $NF}')"
echo "16. A gửi tin sau khi Community chạy lại -> HTTP $code time_total=${secs}s"
[ "$code" = "201" ] || { echo "FAIL: cần HTTP 201"; exit 1; }
echo "OK: phần 503 xong"
