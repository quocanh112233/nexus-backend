#!/bin/bash
set -euo pipefail
# Demo P2: A tạo server và invite 1 lượt, B xem trước + tham gia + rời, C bị 410, B bị 404 sau khi rời
# Gọi qua api-gateway :8080; mỗi lần chạy tốn 6 request /auth/** (3 user x đăng ký + đăng nhập),
# gateway giới hạn ~10/phút/IP nên chạy lần 2 trong vòng 1 phút sẽ bị 429 (chờ 1 phút rồi chạy lại)
# In X-Request-Id của từng bước để tra log trong Grafana/Loki; không in token hay mật khẩu
# Cần curl và jq trên máy chạy script
for tool in curl jq; do
  command -v "$tool" >/dev/null || { echo "FAIL: thiếu $tool, cài $tool rồi chạy lại"; exit 1; }
done
BASE="${BASE_URL:-http://127.0.0.1:8080}"
SUFFIX="$(date +%s)$RANDOM"
PASSWORD="demo-pass-123"
HDR="$(mktemp)"
trap 'rm -f "$HDR"' EXIT

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
  [ "$code" = "429" ] && echo "Gateway giới hạn auth ~10 request/phút/IP: chờ 1 phút rồi chạy lại"
  [ "$code" = "$want" ] || { echo "FAIL: $step cần HTTP $want"; exit 1; }
}
# Kiểm tra biểu thức jq $2 đúng trên body của $1, thoát 1 nếu sai
check() {
  echo "${1% *}" | jq -e "$2" >/dev/null || { echo "FAIL: $3"; exit 1; }
}
# Đăng ký rồi đăng nhập user có tiền tố $1, token đưa vào biến TOKEN_$1 (không in ra)
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

signup a
signup b

out="$(call POST /api/v1/servers -H "$(auth a)" -H 'Content-Type: application/json' \
  -d "{\"name\":\"demo-$SUFFIX\"}")"
expect "$out" 201 "1. A tạo server"
check "$out" ".name == \"demo-$SUFFIX\"" "tên server vừa tạo không khớp"
SERVER_ID="$(echo "${out% *}" | jq -er .id)"

out="$(call POST "/api/v1/servers/$SERVER_ID/invites" -H "$(auth a)" -H 'Content-Type: application/json' \
  -d '{"maxUses":1}')"
expect "$out" 201 "2. A tạo invite maxUses=1"
CODE="$(echo "${out% *}" | jq -er .code)"

out="$(call GET "/api/v1/invites/$CODE")"
expect "$out" 200 "3. B xem trước invite (không token)"
check "$out" ".serverName == \"demo-$SUFFIX\"" "tên server trong preview không khớp"

out="$(call POST "/api/v1/invites/$CODE/accept" -H "$(auth b)")"
expect "$out" 200 "4. B tham gia bằng invite"
check "$out" ".id == \"$SERVER_ID\" and .name == \"demo-$SUFFIX\"" "server trả về khi tham gia không khớp"

out="$(call GET /api/v1/servers -H "$(auth b)")"
expect "$out" 200 "5. B liệt kê server"
check "$out" "map(.id) | index(\"$SERVER_ID\") != null" "B không thấy server vừa tham gia"

out="$(call GET "/api/v1/servers/$SERVER_ID/members" -H "$(auth a)")"
expect "$out" 200 "6. A liệt kê member"
check "$out" '.items | length == 2' "server phải có đúng 2 member"

signup c
out="$(call POST "/api/v1/invites/$CODE/accept" -H "$(auth c)")"
expect "$out" 410 "7. C dùng invite đã hết lượt"
check "$out" '.code == "INVITE_INVALID"' "lỗi 410 phải có code INVITE_INVALID"

out="$(call DELETE "/api/v1/servers/$SERVER_ID/members/@me" -H "$(auth b)")"
expect "$out" 204 "8. B rời server"

out="$(call GET "/api/v1/servers/$SERVER_ID" -H "$(auth b)")"
expect "$out" 404 "9. B xem server sau khi rời"
check "$out" '.code == "NOT_FOUND"' "lỗi 404 phải có code NOT_FOUND"
echo "OK: đủ 9 bước, serverId=$SERVER_ID"
