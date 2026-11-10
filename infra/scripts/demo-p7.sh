#!/bin/bash
set -euo pipefail
# Demo P7: kết bạn (frame FriendRequestSent/Accepted trên /user/queue/events), bạn không chung server thấy ONLINE, DM nhận MessageCreated, hủy kết bạn thì gửi 403, đọc 200, hết thấy ONLINE; mỗi bước in PASS/FAIL, FAIL thoát 1
# Gọi qua api-gateway :8080 (đổi bằng BASE_URL); phải đặt COMPOSE_PROJECT (tên project compose đang chạy stack, đã `up -d --build --wait`)
# Các client STOMP là container node:22-alpine tên "$COMPOSE_PROJECT-p7-stomp-*" trong network "$COMPOSE_PROJECT"_default, xóa khi thoát
# Tốn 6 request /auth/** (3 user x đăng ký + đăng nhập), gateway giới hạn ~10/phút/IP nên chạy lại liền sau đó có thể bị 429
# Container node tải @stomp/stompjs và ws từ npm lúc chạy nên cần mạng; không in token hay mật khẩu
# Cần curl, jq, docker trên máy chạy script
for tool in curl jq docker; do
  command -v "$tool" >/dev/null || { echo "FAIL: thiếu $tool, cài $tool rồi chạy lại"; exit 1; }
done
PROJECT="${COMPOSE_PROJECT:-}"
[ -n "$PROJECT" ] || { echo "FAIL: phải đặt COMPOSE_PROJECT=<project compose đang chạy stack>"; exit 1; }
BASE="${BASE_URL:-http://127.0.0.1:8080}"
SUFFIX="$(date +%s)$RANDOM"
PASSWORD="demo-pass-123"
HDR="$(mktemp)"
CLIENT_PREFIX="$PROJECT-p7-stomp"
NET="${PROJECT}_default"
STOMPJS="@stomp/stompjs@7.1.1"
WSLIB="ws@8.18.3"
OFFLINE_MAX=15

# Dọn file tạm và các container client STOMP khi thoát
trap 'rm -f "$HDR"; docker ps -aq --filter "name=$CLIENT_PREFIX-" | xargs -r docker rm -f >/dev/null 2>&1 || true' EXIT

# Gọi API gateway, ghi header vào $HDR, in "<body> <status>"; $1 method, $2 path, còn lại là tham số curl
call() {
  local method="$1" path="$2"
  shift 2
  curl -sS -X "$method" "$BASE$path" -D "$HDR" -w ' %{http_code}' "$@"
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
# Điều kiện cho wait_for: user $1 thấy user $2 ONLINE
sees_online() { [ "$(presence "$1" "$2" | jq -r '.[0].status // empty')" = ONLINE ]; }

docker network inspect "$NET" >/dev/null 2>&1 || fail "không thấy network $NET của project (stack chưa chạy?)"

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
# Điều kiện cho wait_for: log container $1 chứa chuỗi (grep) $2
client_log_has() {
  local log
  log="$(docker logs "$CLIENT_PREFIX-$1" 2>&1)"
  grep -q -- "$2" <<<"$log"
}
# Chạy container client STOMP tên $1 bằng token của user $2, subscribe các destination $3 (cách nhau dấu phẩy), chờ READY; lỗi STOMP lần đầu (gRPC khởi động) thì thử lại một lần
start_client() {
  local name="$1" who="$2" dests="$3" try tokvar="TOKEN_$2"
  for try in 1 2; do
    docker rm -f "$CLIENT_PREFIX-$name" >/dev/null 2>&1 || true
    TOKEN="${!tokvar}" docker run -d --name "$CLIENT_PREFIX-$name" --network "$NET" \
      -e TOKEN -e WS_URL="ws://api-gateway:8080/ws" -e DESTS="$dests" -e JS="$JS" node:22-alpine \
      sh -c "cd /tmp && npm install --silent --ignore-scripts --no-audit --no-fund $STOMPJS $WSLIB >/dev/null 2>&1 && node -e \"\$JS\"" >/dev/null \
      || fail "không chạy được container node:22-alpine (docker pull bị chặn?)"
    wait_for 120 client_log_has "$name" "READY\|STOMP_ERROR" || true
    client_log_has "$name" READY && return 0
    echo "client $name gặp lỗi STOMP lần $try"
  done
  docker logs "$CLIENT_PREFIX-$name" 2>&1 | grep -E "STOMP_ERROR|CLOSED|ERR" | head -5 || true
  fail "client STOMP $name không subscribe xong (npm install hoặc CONNECT/SUBSCRIBE lỗi)"
}

echo "== Chuẩn bị: 3 user không chung server =="
signup a
signup b
signup c

# Bước 1: B đang online, A mời B, B nhận frame FriendRequestSent trên /user/queue/events
echo "== Bước 1: lời mời kết bạn realtime =="
start_client b b "/user/queue/events"
out="$(call POST /api/v1/friends/requests -H "$(auth a)" -H 'Content-Type: application/json' -d "{\"targetUserId\":\"$ID_b\"}")"
expect "$out" 201 "A mời B"
REQ_ID="$(echo "${out% *}" | jq -er .id)"
wait_for 30 client_log_has b "FriendRequestSent.*$REQ_ID" || fail "1. B không nhận FriendRequestSent trong 30 s"
pass "1. B nhận FriendRequestSent trên /user/queue/events"

# Bước 2: A online, B chấp nhận, A nhận frame FriendRequestAccepted
echo "== Bước 2: chấp nhận lời mời =="
start_client a a "/user/queue/events"
out="$(call POST "/api/v1/friends/requests/$REQ_ID/accept" -H "$(auth b)")"
expect "$out" 200 "B chấp nhận"
wait_for 30 client_log_has a "FriendRequestAccepted.*$REQ_ID" || fail "2. A không nhận FriendRequestAccepted trong 30 s"
pass "2. A nhận FriendRequestAccepted sau khi B accept"

# Bước 3: hai bên đang kết nối thấy nhau ONLINE dù không chung server
echo "== Bước 3: presence giữa bạn không chung server =="
wait_for 30 sees_online a "$ID_b" || fail "3. A không thấy B ONLINE trong 30 s"
wait_for 30 sees_online b "$ID_a" || fail "3. B không thấy A ONLINE trong 30 s"
[ "$(presence c "$ID_a")" = "[]" ] || fail "3. người lạ C phải nhận [] khi hỏi presence của A"
pass "3. A và B (bạn, không chung server) thấy nhau ONLINE, người lạ C nhận []"

# Bước 4: A mở DM, B subscribe topic DM, A gửi tin, B nhận MessageCreated
echo "== Bước 4: DM =="
out="$(call POST /api/v1/dms -H "$(auth a)" -H 'Content-Type: application/json' -d "{\"friendId\":\"$ID_b\"}")"
expect "$out" 201 "A mở DM với B"
DM_ID="$(echo "${out% *}" | jq -er .channelId)"
start_client bdm b "/topic/channels/$DM_ID"
MARK="p7-hello-$SUFFIX"
out="$(call POST "/api/v1/channels/$DM_ID/messages" -H "$(auth a)" -H 'Content-Type: application/json' -d "{\"content\":\"$MARK\"}")"
expect "$out" 201 "A gửi tin vào DM"
wait_for 30 client_log_has bdm "MessageCreated.*$MARK" || fail "4. B không nhận MessageCreated trên /topic/channels/{dmId} trong 30 s"
pass "4. B nhận MessageCreated trên /topic/channels/{dmId} sau khi A gửi tin vào DM"

# Bước 5: A hủy kết bạn: gửi 403, đọc lịch sử 200, B hết thấy A, người lạ 404
echo "== Bước 5: hủy kết bạn =="
out="$(call DELETE "/api/v1/friends/$ID_b" -H "$(auth a)")"
expect "$out" 204 "A hủy kết bạn với B"
out="$(call POST "/api/v1/channels/$DM_ID/messages" -H "$(auth a)" -H 'Content-Type: application/json' -d '{"content":"sau khi hủy"}')"
expect "$out" 403 "A gửi tin vào DM sau khi hủy kết bạn"
out="$(call GET "/api/v1/channels/$DM_ID/messages" -H "$(auth a)")"
expect "$out" 200 "A đọc lịch sử DM sau khi hủy kết bạn"
# Điều kiện cho wait_for: B không còn thấy A (mảng rỗng)
a_hidden() { [ "$(presence b "$ID_a")" = "[]" ]; }
wait_for "$OFFLINE_MAX" a_hidden || fail "5. B vẫn thấy A sau ${OFFLINE_MAX} s kể từ khi hủy kết bạn"
out="$(call GET "/api/v1/channels/$DM_ID/messages" -H "$(auth c)")"
expect "$out" 404 "người lạ C đọc DM"
pass "5. sau khi hủy kết bạn: A gửi 403, đọc 200, B hết thấy A, người lạ 404"
echo "OK: 5 bước đạt, dmId=$DM_ID"
