#!/bin/bash
set -euo pipefail
# Demo P3: A tạo role Mod và overwrite kênh announcements, B (Mod) gửi được còn C thì không, B ban C, C bị 403, B gỡ ban, C vào lại
# Gọi qua api-gateway :8080 (đổi bằng BASE_URL); mỗi lần chạy tốn 6 request /auth/** (3 user x đăng ký + đăng nhập),
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
# Bit quyền theo permission-model.md: SEND_MESSAGES = 6, KICK_MEMBERS = 11, BAN_MEMBERS = 12
SEND_MESSAGES=$((1 << 6))
MOD_PERMISSIONS=$(((1 << 6) | (1 << 11) | (1 << 12)))

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
  out="$(call GET /api/v1/users/me -H "Authorization: Bearer $token")"
  expect "$out" 200 "users/me $who"
  printf -v "ID_$who" '%s' "$(echo "${out% *}" | jq -er .id)"
}
# Header Authorization cho user $1
auth() {
  local var="TOKEN_$1"
  echo "Authorization: Bearer ${!var}"
}

signup a
signup b
signup c

out="$(call POST /api/v1/servers -H "$(auth a)" -H 'Content-Type: application/json' \
  -d "{\"name\":\"demo-$SUFFIX\"}")"
expect "$out" 201 "1. A tạo server"
SERVER_ID="$(echo "${out% *}" | jq -er .id)"
S="/api/v1/servers/$SERVER_ID"

out="$(call POST "$S/channels" -H "$(auth a)" -H 'Content-Type: application/json' \
  -d '{"name":"announcements"}')"
expect "$out" 201 "2. A tạo kênh announcements"
CHANNEL_ID="$(echo "${out% *}" | jq -er .id)"

out="$(call POST "$S/roles" -H "$(auth a)" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Mod\",\"permissions\":$MOD_PERMISSIONS}")"
expect "$out" 201 "3. A tạo role Mod (SEND_MESSAGES|KICK_MEMBERS|BAN_MEMBERS)"
check "$out" ".permissions == $MOD_PERMISSIONS" "permissions của role Mod không khớp"
MOD_ID="$(echo "${out% *}" | jq -er .id)"

out="$(call GET "$S/roles" -H "$(auth a)")"
expect "$out" 200 "4. A liệt kê role để lấy @everyone"
EVERYONE_ID="$(echo "${out% *}" | jq -er 'map(select(.isDefault)) | .[0].id')"

out="$(call PUT "$S/channels/$CHANNEL_ID/permissions/role/$EVERYONE_ID" -H "$(auth a)" \
  -H 'Content-Type: application/json' -d "{\"allow\":0,\"deny\":$SEND_MESSAGES}")"
expect "$out" 204 "5. A đặt overwrite @everyone deny SEND_MESSAGES ở announcements"

out="$(call PUT "$S/channels/$CHANNEL_ID/permissions/role/$MOD_ID" -H "$(auth a)" \
  -H 'Content-Type: application/json' -d "{\"allow\":$SEND_MESSAGES,\"deny\":0}")"
expect "$out" 204 "6. A đặt overwrite Mod allow SEND_MESSAGES ở announcements"

out="$(call POST "$S/invites" -H "$(auth a)" -H 'Content-Type: application/json' -d '{}')"
expect "$out" 201 "7. A tạo invite không giới hạn lượt"
CODE="$(echo "${out% *}" | jq -er .code)"

out="$(call POST "/api/v1/invites/$CODE/accept" -H "$(auth b)")"
expect "$out" 200 "8. B tham gia bằng invite"
out="$(call POST "/api/v1/invites/$CODE/accept" -H "$(auth c)")"
expect "$out" 200 "9. C tham gia bằng invite"

out="$(call PUT "$S/members/$ID_b/roles" -H "$(auth a)" -H 'Content-Type: application/json' \
  -d "{\"roleIds\":[\"$MOD_ID\"]}")"
expect "$out" 200 "10. A gán role Mod cho B"

out="$(call GET "$S/permissions/@me" -H "$(auth b)")"
expect "$out" 200 "11. B xem permissions/@me"
check "$out" ".channels[] | select(.channelId == \"$CHANNEL_ID\") | (.permissions / $SEND_MESSAGES | floor) % 2 == 1" \
  "B (Mod) phải gửi được ở announcements"
check "$out" '(.server / 2048 | floor) % 2 == 1 and (.server / 4096 | floor) % 2 == 1' \
  "B phải có KICK_MEMBERS và BAN_MEMBERS"

out="$(call GET "$S/permissions/@me" -H "$(auth c)")"
expect "$out" 200 "12. C xem permissions/@me"
check "$out" ".channels[] | select(.channelId == \"$CHANNEL_ID\") | (.permissions / $SEND_MESSAGES | floor) % 2 == 0" \
  "C (member thường) không được gửi ở announcements"

out="$(call PUT "$S/bans/$ID_c" -H "$(auth b)" -H 'Content-Type: application/json' \
  -d '{"reason":"spam"}')"
expect "$out" 204 "13. B ban C kèm lý do"

out="$(call POST "/api/v1/invites/$CODE/accept" -H "$(auth c)")"
expect "$out" 403 "14. C tham gia lại khi đang bị ban"
check "$out" '.code == "BANNED_FROM_SERVER"' "lỗi 403 phải có code BANNED_FROM_SERVER"

out="$(call GET "$S/bans" -H "$(auth b)")"
expect "$out" 200 "15. B liệt kê ban"
check "$out" ".items | length == 1 and .[0].userId == \"$ID_c\" and .[0].reason == \"spam\"" \
  "danh sách ban phải có đúng 1 mục của C với lý do spam"

out="$(call DELETE "$S/bans/$ID_c" -H "$(auth b)")"
expect "$out" 204 "16. B gỡ ban C"

out="$(call POST "/api/v1/invites/$CODE/accept" -H "$(auth c)")"
expect "$out" 200 "17. C tham gia lại sau khi được gỡ ban"
echo "OK: đủ 17 bước, serverId=$SERVER_ID"
