#!/bin/bash
set -euo pipefail
for tool in curl jq docker; do
  command -v "$tool" >/dev/null || { echo "FAIL: thiếu $tool, cài $tool rồi chạy lại"; exit 1; }
done
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROJECT="${COMPOSE_PROJECT:-}"
[ -n "$PROJECT" ] || { echo "FAIL: phải đặt COMPOSE_PROJECT=<project compose đang chạy stack>"; exit 1; }
BASE="${BASE_URL:-http://127.0.0.1:8080}"
STATE_DIR="$ROOT/infra/perf/.state"
STATE="$STATE_DIR/state.json"
PASSWORD="perf-pass-123"
USERS_N=50
LOGIN_ONLY=0

# In FAIL cho lý do $1 ra stderr rồi thoát 1
fail() { echo "FAIL: $1" >&2; exit 1; }
# Chạy docker compose của project $COMPOSE_PROJECT từ thư mục gốc repo, giữ nguyên môi trường của người gọi (POSTGRES_PORT...)
dc() { (cd "$ROOT" && docker compose -p "$PROJECT" "$@"); }
# Chờ tối đa $1 giây tới khi lệnh còn lại thành công, trả 1 nếu hết giờ
wait_for() {
  local max="$1" i
  shift
  for ((i = 0; i < max; i++)); do
    "$@" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
# Gọi API gateway, in body và thoát 1 nếu mã HTTP khác $1; $2 method, $3 path, còn lại là tham số curl
api() {
  local want="$1" method="$2" path="$3" out
  shift 3
  out="$(curl -sS -X "$method" "$BASE$path" -w ' %{http_code}' "$@")" || fail "$method $path không gọi được $BASE"
  [ "${out##* }" = "$want" ] || fail "$method $path cần HTTP $want, nhận ${out##* }"
  echo "${out% *}"
}
# Header Authorization cho token $1
auth() { echo "Authorization: Bearer $1"; }
# Tạo lại api-gateway và chờ healthy, in log compose chỉ khi lỗi; môi trường truyền từ người gọi
gateway_up() {
  local out
  out="$(dc up -d --wait api-gateway 2>&1)" || { echo "$out" >&2; return 1; }
}
# Bật lại rate limit (về mặc định true) khi thoát; lỗi bật lại thì thoát 1 dù script đang thành công
restore_ratelimit() {
  local rc=$?
  trap - EXIT
  echo "Bật lại rate limit của api-gateway"
  (unset RATELIMIT_ENABLED; gateway_up) \
    || { echo "FAIL: không bật lại được RATELIMIT_ENABLED, tự chạy: docker compose -p $PROJECT up -d --wait api-gateway" >&2; rc=1; }
  exit "$rc"
}
# Đăng nhập email $1, in accessToken
login() {
  local body
  body="$(api 200 POST /api/v1/auth/login -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$PASSWORD\"}")"
  jq -er .accessToken <<<"$body" || fail "login $1 không trả accessToken"
}
# Đăng ký user có email $1, username $2, in id
register() {
  local body
  body="$(api 201 POST /api/v1/auth/register -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"username\":\"$2\",\"password\":\"$PASSWORD\"}")"
  jq -er .id <<<"$body" || fail "register $1 không trả id"
}
# Điều kiện cho wait_for: messaging.channel_refs đã có kênh $1
channel_ref_exists() {
  [ -n "$(dc exec -T postgres psql -U postgres -d nexus -tAc "select 1 from messaging.channel_refs where channel_id = '$1'")" ]
}
# Đăng nhập lại mọi user trong state cũ, chỉ thay accessToken
relogin() {
  [ -f "$STATE" ] || fail "chưa có $STATE, chạy setup.sh không có --login-only trước"
  local users i n email token
  jq -e '.users | length > 0' "$STATE" >/dev/null || fail "$STATE không có danh sách users hợp lệ, chạy setup.sh không có --login-only"
  users="$(jq -c .users "$STATE")"
  n="$(jq -r length <<<"$users")"
  for ((i = 0; i < n; i++)); do
    email="$(jq -r ".[$i].email" <<<"$users")"
    token="$(login "$email")"
    users="$(jq -c --argjson i "$i" --arg t "$token" '.[$i].accessToken = $t' <<<"$users")"
  done
  jq --argjson u "$users" '.users = $u' "$STATE" >"$STATE.tmp"
  mv "$STATE.tmp" "$STATE"
  echo "Đã đăng nhập lại $n user, channelId giữ nguyên"
}
# Dựng $USERS_N user, server, kênh; user 0 là chủ server, các user còn lại join bằng invite; ghi $STATE
create_all() {
  local suffix users i email id token server_id channel_id code
  suffix="$(date +%s)$RANDOM"
  users='[]'
  for ((i = 0; i < USERS_N; i++)); do
    email="perf-$suffix-$i@nexus.test"
    id="$(register "$email" "perf${suffix}u$i")"
    token="$(login "$email")"
    users="$(jq -c --arg id "$id" --arg e "$email" --arg p "$PASSWORD" --arg t "$token" \
      '. + [{id: $id, email: $e, password: $p, accessToken: $t}]' <<<"$users")"
  done
  token="$(jq -r '.[0].accessToken' <<<"$users")"
  server_id="$(api 201 POST /api/v1/servers -H "$(auth "$token")" -H 'Content-Type: application/json' \
    -d "{\"name\":\"perf-$suffix\"}" | jq -er .id)"
  channel_id="$(api 201 POST "/api/v1/servers/$server_id/channels" -H "$(auth "$token")" -H 'Content-Type: application/json' \
    -d '{"name":"perf"}' | jq -er .id)"
  code="$(api 201 POST "/api/v1/servers/$server_id/invites" -H "$(auth "$token")" -H 'Content-Type: application/json' \
    -d '{}' | jq -er .code)"
  for ((i = 1; i < USERS_N; i++)); do
    token="$(jq -r ".[$i].accessToken" <<<"$users")"
    api 200 POST "/api/v1/invites/$code/accept" -H "$(auth "$token")" >/dev/null
  done
  wait_for 120 channel_ref_exists "$channel_id" || fail "messaging.channel_refs chưa có kênh $channel_id sau 120 s"
  mkdir -p "$STATE_DIR"
  jq -n --arg s "$server_id" --arg c "$channel_id" --arg t "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --argjson u "$users" \
    '{serverId: $s, channelId: $c, createdAt: $t, users: $u}' >"$STATE.tmp"
  mv "$STATE.tmp" "$STATE"
  echo "Đã dựng $USERS_N user, serverId=$server_id, channelId=$channel_id"
}
# Cách dùng: COMPOSE_PROJECT=nexus-perf setup.sh [--users N] [--login-only]; tắt rate limit tạm thời để đăng ký hàng loạt, trap EXIT bật lại
main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --users) USERS_N="${2:-}"; shift 2 || fail "--users cần giá trị" ;;
      --login-only) LOGIN_ONLY=1; shift ;;
      *) fail "tham số lạ: $1" ;;
    esac
  done
  [[ "$USERS_N" =~ ^[1-9][0-9]*$ ]] || fail "--users phải là số nguyên dương"
  trap restore_ratelimit EXIT
  echo "Tắt rate limit của api-gateway để đăng ký/đăng nhập hàng loạt"
  RATELIMIT_ENABLED=false gateway_up
  if [ "$LOGIN_ONLY" = 1 ]; then relogin; else create_all; fi
}

main "$@"
