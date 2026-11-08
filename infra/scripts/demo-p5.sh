#!/bin/bash
set -euo pipefail
# Demo P5: bài tập message queue 1 (eventual consistency), 3 (payload hỏng vào DLQ), 5 (RabbitMQ chết vẫn 201, outbox relay sau); mỗi bước in PASS/FAIL, FAIL thoát 1
# Gọi qua api-gateway :8080 (đổi bằng BASE_URL) và RabbitMQ Management 127.0.0.1:15672 vhost nexus (đổi bằng RMQ_URL)
# Phải đặt COMPOSE_PROJECT (tên project compose đang chạy stack) vì script dừng/bật messaging-service và rabbitmq của project đó
# Tốn 2 request /auth/** (đăng ký + đăng nhập), gateway giới hạn ~10/phút/IP nên chạy lại liền sau đó có thể bị 429
# Tài khoản RabbitMQ lấy từ RABBITMQ_ADMIN_USER/RABBITMQ_ADMIN_PASSWORD (env hoặc .env gốc repo), không in ra; không in token hay mật khẩu
# Cần curl, jq, docker compose trên máy chạy script
for tool in curl jq docker; do
  command -v "$tool" >/dev/null || { echo "FAIL: thiếu $tool, cài $tool rồi chạy lại"; exit 1; }
done
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROJECT="${COMPOSE_PROJECT:-}"
[ -n "$PROJECT" ] || { echo "FAIL: phải đặt COMPOSE_PROJECT=<project compose đang chạy stack>"; exit 1; }
BASE="${BASE_URL:-http://127.0.0.1:8080}"
RMQ="${RMQ_URL:-http://127.0.0.1:15672}"
VHOST="nexus"
SUFFIX="$(date +%s)$RANDOM"
PASSWORD="demo-pass-123"
HDR="$(mktemp)"
STOPPED=""

# Đọc biến $1 từ env, không có thì từ .env gốc repo (không source file để khỏi chạy lệnh lạ)
envvar() {
  local v="${!1:-}"
  [ -n "$v" ] || v="$(grep -E "^$1=" "$ROOT/.env" 2>/dev/null | head -1 | cut -d= -f2- || true)"
  echo "$v"
}
RMQ_USER="$(envvar RABBITMQ_ADMIN_USER)"
RMQ_PASS="$(envvar RABBITMQ_ADMIN_PASSWORD)"
[ -n "$RMQ_USER" ] && [ -n "$RMQ_PASS" ] || { echo "FAIL: thiếu RABBITMQ_ADMIN_USER/RABBITMQ_ADMIN_PASSWORD"; exit 1; }

# Chạy docker compose của project $COMPOSE_PROJECT từ thư mục gốc repo
dc() { (cd "$ROOT" && docker compose -p "$PROJECT" "$@"); }
# Dọn file tạm và bật lại service đã dừng nếu script thoát giữa chừng
trap 'rm -f "$HDR"; for s in $STOPPED; do dc start "$s" >/dev/null 2>&1 || true; done' EXIT

# Gọi API gateway, ghi header vào $HDR, in "<body> <status>"; $1 method, $2 path, còn lại là tham số curl
call() {
  local method="$1" path="$2"
  shift 2
  curl -sS -X "$method" "$BASE$path" -D "$HDR" -w ' %{http_code}' "$@"
}
# Gọi RabbitMQ Management API với tài khoản admin (qua config stdin để mật khẩu không lộ trên dòng lệnh); $1 method, $2 path, còn lại là tham số curl
rmq() {
  local method="$1" path="$2"
  shift 2
  printf 'user = "%s:%s"\n' "$RMQ_USER" "$RMQ_PASS" | curl -sS -K - -X "$method" "$RMQ$path" "$@"
}
# Chạy SQL $1 bằng psql admin trong container postgres, in giá trị thô
sql() { dc exec -T postgres psql -U postgres -d nexus -tA -c "$1"; }
# Số message trong queue $1 (vhost nexus)
qmsgs() { rmq GET "/api/queues/$VHOST/$1" | jq -r '.messages // 0'; }
# In PASS cho bước $1
pass() { echo "PASS: $1"; }
# In FAIL cho bước $1 và thoát 1
fail() { echo "FAIL: $1"; exit 1; }
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
# Kiểm tra mã HTTP cuối chuỗi $1 bằng $2 cho bước $3, FAIL nếu sai
expect() {
  local code="${1##* }"
  echo "$3 -> HTTP $code"
  [ "$code" != "429" ] || echo "Gateway giới hạn: chờ 1 phút rồi chạy lại"
  [ "$code" = "$2" ] || fail "$3 cần HTTP $2"
}
# Header Authorization của user đã đăng nhập
auth() { echo "Authorization: Bearer $TOKEN"; }

# Điều kiện cho wait_for: broker trả được thông tin queue messaging.community.events
rmq_up() { [ "$(rmq GET "/api/queues/$VHOST/messaging.community.events" -o /dev/null -w '%{http_code}')" = 200 ]; }
rmq_up || fail "RabbitMQ Management ($RMQ) không trả queue messaging.community.events: stack chưa chạy hoặc sai vhost/tài khoản"

out="$(call POST /api/v1/auth/register -H 'Content-Type: application/json' \
  -d "{\"email\":\"p5$SUFFIX@example.com\",\"username\":\"p5$SUFFIX\",\"password\":\"$PASSWORD\"}")"
expect "$out" 201 "register"
out="$(call POST /api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"p5$SUFFIX@example.com\",\"password\":\"$PASSWORD\"}")"
expect "$out" 200 "login"
TOKEN="$(echo "${out% *}" | jq -er .accessToken)" || TOKEN=""
[ -n "$TOKEN" ] || fail "login không trả accessToken"
out="$(call POST /api/v1/servers -H "$(auth)" -H 'Content-Type: application/json' -d "{\"name\":\"p5-$SUFFIX\"}")"
expect "$out" 201 "tạo server"
SERVER_ID="$(echo "${out% *}" | jq -er .id)"
S="/api/v1/servers/$SERVER_ID"
# Điều kiện cho wait_for: exchange $1 đã có trên broker (identity.events chỉ được khai báo khi relay publish lần đầu)
has_exchange() { [ "$(rmq GET "/api/exchanges/$VHOST/$1" | jq -r '.name // empty')" = "$1" ]; }
for ex in identity.events community.events messaging.events; do
  wait_for 30 has_exchange "$ex" || fail "thiếu exchange $ex"
  echo "exchange $ex có trên broker"
done

# Bài 1: messaging tắt, tạo 3 kênh, bật lại, channel_refs đủ 3 dòng
echo "== Bài 1: messaging-service tắt, tạo 3 kênh =="
dc stop messaging-service >/dev/null
STOPPED="messaging-service"
IDS=()
for i in 1 2 3; do
  out="$(call POST "$S/channels" -H "$(auth)" -H 'Content-Type: application/json' -d "{\"name\":\"p5-ch$i\"}")"
  expect "$out" 201 "tạo kênh $i"
  IDS+=("$(echo "${out% *}" | jq -er .id)")
done
LIST="'${IDS[0]}','${IDS[1]}','${IDS[2]}'"
# Điều kiện cho wait_for: queue messaging.community.events giữ ít nhất 3 message chờ (stats của broker cập nhật mỗi vài giây)
queue_has_3() { [ "$(qmsgs messaging.community.events)" -ge 3 ]; }
wait_for 30 queue_has_3 || fail "bài 1: queue messaging.community.events không giữ >= 3 message khi messaging tắt"
echo "queue messaging.community.events đang giữ $(qmsgs messaging.community.events) message"
[ "$(sql "select count(*) from messaging.channel_refs where channel_id in ($LIST)")" = 0 ] || fail "bài 1: channel_refs có kênh khi messaging còn tắt"
dc start messaging-service >/dev/null
STOPPED=""
# Điều kiện cho wait_for: channel_refs đủ 3 dòng của 3 kênh vừa tạo
refs_has_3() { [ "$(sql "select count(*) from messaging.channel_refs where channel_id in ($LIST)")" = 3 ]; }
wait_for 120 refs_has_3 || fail "bài 1: channel_refs không đủ 3 dòng sau 120 s"
pass "1. 3 kênh tạo lúc messaging tắt, bật lại thì channel_refs có đủ 3 dòng (eventual consistency)"

# Bài 3: payload hỏng vào DLQ, queue chính không kẹt
echo "== Bài 3: payload hỏng vào community.events =="
wait_for 120 rmq_up || fail "RabbitMQ không phản hồi"
DLQ=messaging.community.events.dlq
BEFORE="$(qmsgs "$DLQ")"
SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
res="$(rmq POST "/api/exchanges/$VHOST/community.events/publish" -H 'Content-Type: application/json' \
  -d '{"properties":{"delivery_mode":2,"content_type":"application/json"},"routing_key":"channel.deleted","payload":"{khong-phai-json","payload_encoding":"string"}')"
echo "$res" | jq -e '.routed == true' >/dev/null || fail "bài 3: broker không route payload hỏng vào queue nào"
# Điều kiện cho wait_for: DLQ tăng đúng 1 message so với trước khi publish
dlq_plus_1() { [ "$(qmsgs "$DLQ")" = "$((BEFORE + 1))" ]; }
wait_for 60 dlq_plus_1 || fail "bài 3: $DLQ không tăng thêm 1 message (trước: $BEFORE)"
# Điều kiện cho wait_for: queue chính trống (không kẹt, không còn unacked)
main_empty() { [ "$(qmsgs messaging.community.events)" = 0 ]; }
wait_for 30 main_empty || fail "bài 3: queue chính messaging.community.events còn message"
echo "log messaging-service từ $SINCE (lỗi parse không đáng retry nên vào DLQ ngay, không có 3 lần retry):"
dc logs --no-log-prefix --no-color --since "$SINCE" messaging-service 2>&1 \
  | jq -Rr 'fromjson? | select(.log.logger | test("Reject|ErrorHandler")) | "\(.log.level) \(.log.logger | split(".") | last): \(.message)"' | cut -c1-200 || true
pass "3. payload hỏng nằm ở $DLQ (+1 message), queue chính trống"

# Bài 5: RabbitMQ chết, request vẫn 201, bật lại thì outbox được relay
echo "== Bài 5: dừng rabbitmq, tạo server =="
dc stop rabbitmq >/dev/null
STOPPED="rabbitmq"
out="$(call POST /api/v1/servers -H "$(auth)" -H 'Content-Type: application/json' -d "{\"name\":\"p5b-$SUFFIX\"}")"
expect "$out" 201 "tạo server khi RabbitMQ tắt"
SID2="$(echo "${out% *}" | jq -er .id)"
OUTBOX="select count(*) from community.outbox_event where aggregate_id = '$SID2' and event_type = 'ServerCreated'"
[ "$(sql "$OUTBOX and published_at is null")" = 1 ] || fail "bài 5: outbox ServerCreated chưa nằm chờ (published_at phải NULL khi broker tắt)"
dc start rabbitmq >/dev/null
STOPPED=""
# Điều kiện cho wait_for: dòng outbox ServerCreated của server vừa tạo đã có published_at
outbox_sent() { [ "$(sql "$OUTBOX and published_at is not null")" = 1 ]; }
wait_for 180 outbox_sent || fail "bài 5: outbox ServerCreated vẫn published_at NULL sau 180 s"
pass "5. tạo server lúc RabbitMQ tắt trả 201; bật lại thì outbox_event.published_at được điền"
echo "OK: 3 bài đạt, serverId=$SERVER_ID"
