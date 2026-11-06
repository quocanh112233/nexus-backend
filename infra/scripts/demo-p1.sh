#!/bin/bash
set -euo pipefail
# Demo P1: đăng ký, đăng nhập, /users/me, refresh, dùng lại refresh cũ (REFRESH_TOKEN_REUSED)
# Gọi qua api-gateway :8080; mỗi lần chạy tốn 4 request /auth/**, gateway giới hạn ~10/phút/IP nên chạy liên tiếp quá 2 lần trong 1 phút sẽ bị 429
# Cần COOKIE_SECURE=false (mặc định trong compose): cookie Secure qua HTTP thì curl không gửi lại ở bước refresh
BASE="${BASE_URL:-http://127.0.0.1:8080}"
ORIGIN="${DEMO_ORIGIN:-http://localhost:3000}"
SUFFIX="$(date +%s)$RANDOM"
EMAIL="demo$SUFFIX@example.com"
USERNAME="demo$SUFFIX"
PASSWORD="demo-pass-123"
JAR="$(mktemp)"
trap 'rm -f "$JAR" "$JAR.old"' EXIT

# Gọi API, in "<status> <body>" ra stdout; $1 method, $2 path, còn lại là tham số curl
call() {
  local method="$1" path="$2"
  shift 2
  curl -sS -X "$method" "$BASE$path" -w ' %{http_code}' "$@"
}
# Kiểm tra mã HTTP cuối chuỗi $1 bằng $2, in bước $3, thoát 1 nếu sai
expect() {
  local out="$1" want="$2" step="$3" code
  code="${out##* }"
  echo "$step -> HTTP $code $(echo "${out% *}" | cut -c1-160)"
  [ "$code" = "$want" ] || { echo "FAIL: $step cần HTTP $want"; exit 1; }
}

out="$(call POST /api/v1/auth/register -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")"
expect "$out" 201 "1. register"

out="$(call POST /api/v1/auth/login -c "$JAR" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")"
expect "$out" 200 "2. login"
TOKEN="$(echo "${out% *}" | jq -er .accessToken)"
cp "$JAR" "$JAR.old"

out="$(call GET /api/v1/users/me -H "Authorization: Bearer $TOKEN")"
expect "$out" 200 "3. users/me"
[ "$(echo "${out% *}" | jq -r .username)" = "$USERNAME" ] || { echo "FAIL: username không khớp"; exit 1; }

out="$(call POST /api/v1/auth/refresh -b "$JAR" -c "$JAR" -H "Origin: $ORIGIN")"
expect "$out" 200 "4. refresh"

out="$(call POST /api/v1/auth/refresh -b "$JAR.old" -H "Origin: $ORIGIN")"
expect "$out" 401 "5. refresh bằng token cũ"
echo "${out% *}" | jq -e '.code == "REFRESH_TOKEN_REUSED"' >/dev/null || { echo "FAIL: thiếu REFRESH_TOKEN_REUSED"; exit 1; }
echo "OK: đủ 5 bước, REFRESH_TOKEN_REUSED hiển thị"
