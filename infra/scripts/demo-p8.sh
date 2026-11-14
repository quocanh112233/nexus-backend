#!/bin/bash
set -euo pipefail
# Demo P8: mention gửi frame MentionCreated tới người được nhắc, GET /read-states có unread và mentionCount, PUT đánh dấu đã đọc (mốc không lùi) gửi ReadStateUpdated cho thiết bị khác, mention người ngoài server không tạo mention; mỗi bước in PASS/FAIL, FAIL thoát 1
# Gọi qua api-gateway :8080 (đổi bằng BASE_URL); phải đặt COMPOSE_PROJECT (tên project compose đang chạy stack, đã `up -d --build --wait`)
# Các client STOMP là container node:22-alpine tên "$COMPOSE_PROJECT-p8-stomp-*" trong network "$COMPOSE_PROJECT"_default, xóa khi thoát
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
CLIENT_PREFIX="$PROJECT-p8-stomp"
NET="${PROJECT}_default"
STOMPJS="@stomp/stompjs@7.1.1"
WSLIB="ws@8.18.3"

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

echo "== Chuẩn bị: 3 user, server, kênh; A và B cùng server, C chưa vào =="
signup a
signup b
signup c
out="$(call POST /api/v1/servers -H "$(auth a)" -H 'Content-Type: application/json' -d "{\"name\":\"p8-$SUFFIX\"}")"
expect "$out" 201 "A tạo server"
SERVER_ID="$(echo "${out% *}" | jq -er .id)"
CHANNEL_ID="$(echo "${out% *}" | jq -er '.channels[0].id')"
out="$(call POST "/api/v1/servers/$SERVER_ID/invites" -H "$(auth a)" -H 'Content-Type: application/json' -d '{}')"
expect "$out" 201 "A tạo invite"
CODE="$(echo "${out% *}" | jq -er .code)"
out="$(call POST "/api/v1/invites/$CODE/accept" -H "$(auth b)")"
expect "$out" 200 "B tham gia bằng invite"

# Đọc read state của user $1, in JSON phản hồi
read_states() { curl -sS "$BASE/api/v1/read-states" -H "$(auth "$1")"; }
# Điều kiện cho wait_for: kênh demo trong read state của user $1 có mentionCount $2 và unread $3
channel_is() { [ "$(read_states "$1" | jq -r --arg c "$CHANNEL_ID" '.items[] | select(.channelId == $c) | "\(.mentionCount) \(.unread)"')" = "$2 $3" ]; }

# Bước 1: B online hai thiết bị, A gửi tin mention B, B nhận frame MentionCreated
echo "== Bước 1: mention realtime =="
start_client b1 b "/user/queue/events"
start_client b2 b "/user/queue/events"
out="$(call POST "/api/v1/channels/$CHANNEL_ID/messages" -H "$(auth a)" -H 'Content-Type: application/json' -d "{\"content\":\"chào <@$ID_b>\"}")"
expect "$out" 201 "A gửi tin mention B"
MSG_ID="$(echo "${out% *}" | jq -er .id)"
wait_for 30 client_log_has b1 "MentionCreated.*$MSG_ID" || fail "1. B không nhận MentionCreated trong 30 s"
pass "1. B nhận MentionCreated trên /user/queue/events sau khi A mention"

# Bước 2: read state của B có unread và mentionCount 1, của A không unread (tin của chính mình)
echo "== Bước 2: GET /read-states =="
wait_for 30 channel_is b 1 true || fail "2. read state của B cần mentionCount=1 unread=true"
wait_for 30 channel_is a 0 false || fail "2. read state của A cần mentionCount=0 unread=false"
pass "2. B có unread=true mentionCount=1, A có unread=false (tin của mình)"

# Bước 3: B đánh dấu đã đọc tới tin đó, mentionCount về 0, thiết bị thứ hai của B nhận ReadStateUpdated
echo "== Bước 3: đánh dấu đã đọc =="
out="$(call PUT "/api/v1/read-states/$CHANNEL_ID" -H "$(auth b)" -H 'Content-Type: application/json' -d "{\"lastReadMessageId\":\"$MSG_ID\"}")"
expect "$out" 200 "B PUT read-state"
[ "$(echo "${out% *}" | jq -r .mentionCount)" = 0 ] || fail "3. mentionCount của B phải về 0 sau khi đọc"
wait_for 30 client_log_has b2 "ReadStateUpdated.*$MSG_ID" || fail "3. thiết bị thứ hai của B không nhận ReadStateUpdated trong 30 s"
pass "3. B đọc tới tin mention: mentionCount=0, thiết bị STOMP thứ hai nhận ReadStateUpdated"

# Bước 4: A gửi thêm một tin, B PUT lại với id tin cũ hơn thì mốc đọc không lùi
echo "== Bước 4: mốc đọc không lùi =="
out="$(call POST "/api/v1/channels/$CHANNEL_ID/messages" -H "$(auth a)" -H 'Content-Type: application/json' -d '{"content":"tin thứ hai"}')"
expect "$out" 201 "A gửi tin thứ hai"
MSG2_ID="$(echo "${out% *}" | jq -er .id)"
out="$(call PUT "/api/v1/read-states/$CHANNEL_ID" -H "$(auth b)" -H 'Content-Type: application/json' -d "{\"lastReadMessageId\":\"$MSG2_ID\"}")"
expect "$out" 200 "B PUT tới tin thứ hai"
out="$(call PUT "/api/v1/read-states/$CHANNEL_ID" -H "$(auth b)" -H 'Content-Type: application/json' -d "{\"lastReadMessageId\":\"$MSG_ID\"}")"
expect "$out" 200 "B PUT lùi về tin đầu"
[ "$(echo "${out% *}" | jq -r .lastReadMessageId)" = "$MSG2_ID" ] || fail "4. lastReadMessageId phải giữ $MSG2_ID, không lùi về tin cũ"
pass "4. PUT với id nhỏ hơn không làm lastReadMessageId lùi"

# Bước 5: A mention C (chưa vào server), C không có mention nào
echo "== Bước 5: mention người ngoài server =="
out="$(call POST "/api/v1/channels/$CHANNEL_ID/messages" -H "$(auth a)" -H 'Content-Type: application/json' -d "{\"content\":\"gọi <@$ID_c>\"}")"
expect "$out" 201 "A mention C (chưa vào server)"
# Điều kiện cho wait_for: B đã thấy tin mention thứ hai (chứng tỏ notification đã xử lý tin này)
b_mentioned_again() { [ "$(read_states b | jq -r --arg c "$CHANNEL_ID" '.items[] | select(.channelId == $c) | .unread')" = true ]; }
wait_for 30 b_mentioned_again || fail "5. notification chưa xử lý tin mention C trong 30 s"
[ "$(read_states c | jq -c .items)" = "[]" ] || fail "5. C chưa vào server phải có read state rỗng"
pass "5. C (ngoài server) không có read state hay mentionCount"
echo "OK: 5 bước đạt, serverId=$SERVER_ID"
