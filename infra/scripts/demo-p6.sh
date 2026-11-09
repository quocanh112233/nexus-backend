#!/bin/bash
set -euo pipefail
# Demo P6: 2 instance realtime-gateway, STOMP qua api-gateway nhận MessageCreated, presence ONLINE/riêng tư, kill -9 một instance thì user OFFLINE; mỗi bước in PASS/FAIL, FAIL thoát 1
# Gọi qua api-gateway :8080 (đổi bằng BASE_URL) và RabbitMQ Management 127.0.0.1:15672 vhost nexus (đổi bằng RMQ_URL)
# Phải đặt COMPOSE_PROJECT (tên project compose đang chạy stack): script chạy một container node:22-alpine tên "$COMPOSE_PROJECT-p6-stomp" trong network "$COMPOSE_PROJECT"_default và kill -9 một replica realtime-gateway của project đó, không đụng thứ khác
# Các biến GRPC_TOKEN_* và mật khẩu stack phải có trong env hoặc .env gốc repo để docker compose nội suy được file compose
# Tốn 6 request /auth/** (3 user x đăng ký + đăng nhập), gateway giới hạn ~10/phút/IP nên chạy lại liền sau đó có thể bị 429
# Container node tải @stomp/stompjs và ws từ npm lúc chạy nên cần mạng; không in token hay mật khẩu
# Bước 4 kill một replica; khi thoát script chạy lại `docker compose up -d --no-deps realtime-gateway` của project để bật lại replica đó
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
CLIENT="$PROJECT-p6-stomp"
STOMPJS="@stomp/stompjs@7.1.1"
WSLIB="ws@8.18.3"
OFFLINE_MAX=90

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
KILLED=""
# Dọn file tạm, container client STOMP và bật lại replica đã kill (nếu có) khi thoát
trap 'rm -f "$HDR"; docker rm -f "$CLIENT" >/dev/null 2>&1 || true; [ -z "$KILLED" ] || dc up -d --no-deps realtime-gateway >/dev/null 2>&1 || true' EXIT

# Gọi API gateway, ghi header vào $HDR, in "<body> <status>"; $1 method, $2 path, còn lại là tham số curl
call() {
  local method="$1" path="$2"
  shift 2
  curl -sS -X "$method" "$BASE$path" -D "$HDR" -w ' %{http_code}' "$@"
}
# Gọi RabbitMQ Management API với tài khoản admin (qua config stdin để mật khẩu không lộ trên dòng lệnh); $1 method, $2 path
rmq() {
  local method="$1" path="$2"
  shift 2
  printf 'user = "%s:%s"\n' "$RMQ_USER" "$RMQ_PASS" | curl -sS -K - -X "$method" "$RMQ$path" "$@"
}
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
# Đăng ký, đăng nhập user có tiền tố $1; token vào TOKEN_$1 (không in ra), userId vào ID_$1
signup() {
  local who="$1" out token id
  out="$(call POST /api/v1/auth/register -H 'Content-Type: application/json' \
    -d "{\"email\":\"$who$SUFFIX@example.com\",\"username\":\"$who$SUFFIX\",\"password\":\"$PASSWORD\"}")"
  expect "$out" 201 "register $who"
  id="$(echo "${out% *}" | jq -er .id)" || fail "register $who không trả id"
  printf -v "ID_$who" '%s' "$id"
  out="$(call POST /api/v1/auth/login -H 'Content-Type: application/json' \
    -d "{\"email\":\"$who$SUFFIX@example.com\",\"password\":\"$PASSWORD\"}")"
  expect "$out" 200 "login $who"
  token="$(echo "${out% *}" | jq -er .accessToken)" || token=""
  [ -n "$token" ] || fail "login $who không trả accessToken"
  printf -v "TOKEN_$who" '%s' "$token"
}
# Header Authorization cho user $1
auth() {
  local var="TOKEN_$1"
  echo "Authorization: Bearer ${!var}"
}
# Trạng thái presence của user $2 do user $1 xem, in mảng JSON phản hồi
presence() { curl -sS "$BASE/api/v1/presence?userIds=$2" -H "$(auth "$1")"; }

# Điều kiện cho wait_for: broker trả được danh sách queue của vhost nexus
rmq_up() { [ "$(rmq GET "/api/queues/$VHOST" -o /dev/null -w '%{http_code}')" = 200 ]; }
rmq_up || fail "RabbitMQ Management ($RMQ) không phản hồi: stack chưa chạy hoặc sai vhost/tài khoản"

# Bước 1: đúng 2 queue realtime-gateway.* (mỗi replica một queue riêng)
echo "== Bước 1: hai instance realtime-gateway =="
# Điều kiện cho wait_for: broker có đúng 2 queue realtime-gateway.*
two_queues() { [ "$(rmq GET "/api/queues/$VHOST" | jq '[.[] | select(.name | startswith("realtime-gateway."))] | length')" = 2 ]; }
wait_for 90 two_queues || fail "1. broker không có đúng 2 queue realtime-gateway.* sau 90 s"
rmq GET "/api/queues/$VHOST" | jq -r '.[] | select(.name | startswith("realtime-gateway.")) | "queue \(.name)"'
pass "1. RabbitMQ có đúng 2 queue realtime-gateway.* (2 replica)"

echo "== Chuẩn bị: 3 user, server, kênh =="
signup a
signup b
signup c
out="$(call POST /api/v1/servers -H "$(auth a)" -H 'Content-Type: application/json' -d "{\"name\":\"p6-$SUFFIX\"}")"
expect "$out" 201 "A tạo server"
SERVER_ID="$(echo "${out% *}" | jq -er .id)"
CHANNEL_ID="$(echo "${out% *}" | jq -er '.channels[0].id')"
out="$(call POST "/api/v1/servers/$SERVER_ID/invites" -H "$(auth a)" -H 'Content-Type: application/json' -d '{}')"
expect "$out" 201 "A tạo invite"
CODE="$(echo "${out% *}" | jq -er .code)"
out="$(call POST "/api/v1/invites/$CODE/accept" -H "$(auth b)")"
expect "$out" 200 "B tham gia bằng invite"

# Bước 2: client STOMP của B chạy trong container node, đi qua api-gateway, nhận MessageCreated khi A gửi tin
echo "== Bước 2: STOMP qua api-gateway =="
NET="${PROJECT}_default"
docker network inspect "$NET" >/dev/null 2>&1 || fail "2. không thấy network $NET của project"
JS='
const { Client } = require("@stomp/stompjs");
global.WebSocket = require("ws");
const dests = process.env.DESTS.split(",");
let failed = false;
const c = new Client({
  brokerURL: process.env.WS_URL,
  connectHeaders: { Authorization: "Bearer " + process.env.TOKEN },
  heartbeatIncoming: 10000, heartbeatOutgoing: 10000, reconnectDelay: 0,
  onConnect: () => {
    dests.forEach((d) => c.subscribe(d, (m) => { const f = JSON.parse(m.body); console.log("FRAME " + d + " " + f.type + " " + JSON.stringify(f.data)); }));
    setTimeout(() => { if (!failed) console.log("READY"); }, 3000);
  },
  onStompError: (f) => { failed = true; console.log("STOMP_ERROR " + f.headers.message); },
  onWebSocketClose: () => console.log("CLOSED"),
});
c.activate();
'
# Điều kiện cho wait_for: log container client STOMP chứa chuỗi $1
client_log_has() {
  local log
  log="$(docker logs "$CLIENT" 2>&1)"
  grep -q -- "$1" <<<"$log"
}
# Điều kiện cho wait_for: client đã READY hoặc đã gặp lỗi STOMP
client_settled() { client_log_has "READY\|STOMP_ERROR"; }
# Chạy container client STOMP của B (không có receipt từ server nên READY = 3 s sau subscribe không lỗi)
start_client() {
  export TOKEN="$TOKEN_b"
  docker rm -f "$CLIENT" >/dev/null 2>&1 || true
  docker run -d --name "$CLIENT" --network "$NET" \
    -e TOKEN -e WS_URL="ws://api-gateway:8080/ws" \
    -e DESTS="/topic/channels/$CHANNEL_ID,/topic/servers/$SERVER_ID,/user/queue/events" \
    -e JS="$JS" node:22-alpine \
    sh -c "cd /tmp && npm install --silent --ignore-scripts --no-audit --no-fund $STOMPJS $WSLIB >/dev/null 2>&1 && node -e \"\$JS\"" >/dev/null \
    || fail "2. không chạy được container node:22-alpine (docker pull bị chặn?)"
  wait_for 120 client_settled || true
}
start_client
# Lần subscribe đầu sau khi service khởi động có thể quá hạn gRPC (fail-closed), thử lại một lần
client_log_has STOMP_ERROR && { echo "client gặp lỗi STOMP lần đầu, thử lại một lần"; start_client; }
client_log_has READY || { docker logs "$CLIENT" 2>&1 | grep -E "STOMP_ERROR|CLOSED|ERR" | head -5 || true; fail "2. client STOMP không subscribe xong (npm install hoặc CONNECT/SUBSCRIBE lỗi)"; }
MARK="p6-hello-$SUFFIX"
out="$(call POST "/api/v1/channels/$CHANNEL_ID/messages" -H "$(auth a)" -H 'Content-Type: application/json' -d "{\"content\":\"$MARK\"}")"
expect "$out" 201 "A gửi tin vào kênh"
# Điều kiện cho wait_for: client của B nhận frame MessageCreated chứa nội dung vừa gửi
got_message() {
  local log
  log="$(docker logs "$CLIENT" 2>&1)"
  grep -q "MessageCreated.*$MARK" <<<"$log"
}
wait_for 30 got_message || fail "2. client STOMP của B không nhận MessageCreated trong 30 s"
pass "2. B nhận MessageCreated qua /topic/channels/{id} sau khi A POST tin (STOMP đi qua api-gateway)"

# Bước 3: người chung server thấy ONLINE, người lạ nhận mảng rỗng (không OFFLINE giả)
echo "== Bước 3: presence =="
# Điều kiện cho wait_for: A thấy B ONLINE
b_online() { [ "$(presence a "$ID_b" | jq -r '.[0].status // empty')" = ONLINE ]; }
wait_for 30 b_online || fail "3. A không thấy B ONLINE trong 30 s"
[ "$(presence c "$ID_b")" = "[]" ] || fail "3. người lạ C phải nhận [] khi hỏi presence của B"
pass "3. A (chung server) thấy B ONLINE, C (người lạ) nhận []"

# Bước 4: kill -9 replica giữ session của B, B phải về OFFLINE nhờ job dọn instance chết
echo "== Bước 4: kill -9 replica giữ session của B =="
TARGET=""
for cid in $(dc ps -q realtime-gateway); do
  host="${cid:0:12}"
  n="$(dc exec -T redis redis-cli ZCARD "presence:instance:$host:sessions" | tr -d '\r')"
  if [ "${n:-0}" -ge 1 ]; then TARGET="$cid"; break; fi
done
[ -n "$TARGET" ] || fail "4. không tìm được replica realtime-gateway đang giữ session"
echo "kill -9 replica ${TARGET:0:12} của project $PROJECT"
KILLED=1
docker kill -s KILL "$TARGET" >/dev/null
START=$SECONDS
# Điều kiện cho wait_for: A thấy B OFFLINE
b_offline() { [ "$(presence a "$ID_b" | jq -r '.[0].status // empty')" = OFFLINE ]; }
wait_for "$OFFLINE_MAX" b_offline || fail "4. B vẫn ONLINE sau ${OFFLINE_MAX} s kể từ khi kill replica"
pass "4. B về OFFLINE sau $((SECONDS - START)) s kể từ khi kill -9 replica giữ session (dead-instance job, ngưỡng $OFFLINE_MAX s)"
echo "OK: 4 bước đạt, serverId=$SERVER_ID"
