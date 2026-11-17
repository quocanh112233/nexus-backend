#!/bin/bash
set -euo pipefail
for tool in jq docker; do
  command -v "$tool" >/dev/null || { echo "FAIL: thiếu $tool, cài $tool rồi chạy lại"; exit 1; }
done
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROJECT="${COMPOSE_PROJECT:-}"
[ -n "$PROJECT" ] || { echo "FAIL: phải đặt COMPOSE_PROJECT=<project compose đang chạy stack>"; exit 1; }
STATE="$ROOT/infra/perf/.state/state.json"
COUNT="${1:-1000000}"
NOISE_CHANNELS="${NOISE_CHANNELS:-20}"
NOISE_PER_CHANNEL="${NOISE_PER_CHANNEL:-50000}"

# In FAIL cho lý do $1 ra stderr rồi thoát 1
fail() { echo "FAIL: $1" >&2; exit 1; }
# Chạy psql trong container postgres của project $COMPOSE_PROJECT, dừng ở lỗi SQL đầu tiên; $@ là tham số psql, SQL đọc từ stdin
psql_nexus() { (cd "$ROOT" && docker compose -p "$PROJECT" exec -T postgres psql -U postgres -d nexus -v ON_ERROR_STOP=1 "$@"); }
# Đếm tin của kênh $1 cộng với tin nhiễu (content 'noise %' của tác giả $2)
count_existing() { psql_nexus -tA -c "select count(*) from messaging.messages where channel_id = '$1' or (author_id = '$2' and content like 'noise %')"; }
# Đếm tin của kênh $1
count_messages() { psql_nexus -tA -c "select count(*) from messaging.messages where channel_id = '$1'"; }
# Đếm tin nhiễu của tác giả $1
count_noise() { psql_nexus -tA -c "select count(*) from messaging.messages where author_id = '$1' and content like 'noise %'"; }
# Chèn $3 tin vào kênh $1 do tác giả $2 rồi $4 kênh nhiễu mỗi kênh $5 tin, cùng một transaction nên now() chung và id đan xen theo thời gian; id UUIDv7 dựng trong SQL (D4: ghi thẳng DB, bỏ qua outbox)
insert_messages() {
  psql_nexus -v channel="$1" -v author="$2" -v n="$3" -v k="$4" -v m="$5" <<'SQL'
BEGIN;
INSERT INTO messaging.messages (id, channel_id, author_id, content, nonce, created_at, version)
SELECT (lpad(to_hex((extract(epoch FROM ts) * 1000)::bigint), 12, '0')
          || '7' || substr(md5(random()::text), 1, 3)
          || '8' || substr(md5(random()::text), 1, 15))::uuid,
       :'channel'::uuid, :'author'::uuid, 'seed ' || g, NULL, ts, 0
FROM (SELECT g, now() - make_interval(secs => :n) + make_interval(secs => g) AS ts
      FROM generate_series(1, :n) AS g) s;
WITH ch AS MATERIALIZED (SELECT gen_random_uuid() AS cid FROM generate_series(1, :k))
INSERT INTO messaging.messages (id, channel_id, author_id, content, nonce, created_at, version)
SELECT (lpad(to_hex((extract(epoch FROM ts) * 1000)::bigint), 12, '0')
          || '7' || substr(md5(random()::text), 1, 3)
          || '8' || substr(md5(random()::text), 1, 15))::uuid,
       cid, :'author'::uuid, 'noise ' || g, NULL, ts, 0
FROM (SELECT ch.cid, g, now() - make_interval(secs => :n) + make_interval(secs => g * (:n)::float8 / :m) AS ts
      FROM ch CROSS JOIN generate_series(1, :m) AS g) s;
COMMIT;
ANALYZE messaging.messages;
SQL
}
# In id của tin thứ $2 (tính từ cũ nhất) trong kênh $1
mid_message_id() {
  psql_nexus -tA -c "select id from messaging.messages where channel_id = '$1' order by id asc offset $(($2 - 1)) limit 1"
}
# Cách dùng: [RESET=1, xóa rồi VACUUM FULL] [NOISE_CHANNELS=20] [NOISE_PER_CHANNEL=50000] COMPOSE_PROJECT=nexus-perf seed-messages.sh [COUNT]; chèn COUNT tin (mặc định 1000000) vào kênh trong state.json cùng tin nhiễu ở các kênh khác rồi ghi midMessageId (tin thứ COUNT/2 của kênh đo) vào state
main() {
  [[ "$COUNT" =~ ^[1-9][0-9]*$ ]] || fail "COUNT phải là số nguyên dương"
  [[ "$NOISE_CHANNELS" =~ ^[0-9]+$ ]] || fail "NOISE_CHANNELS phải là số nguyên không âm"
  [[ "$NOISE_PER_CHANNEL" =~ ^[1-9][0-9]*$ ]] || fail "NOISE_PER_CHANNEL phải là số nguyên dương"
  [ -f "$STATE" ] || fail "chưa có $STATE, chạy setup.sh trước"
  local channel author existing mid
  channel="$(jq -er .channelId "$STATE")"
  author="$(jq -er '.users[0].id' "$STATE")"
  existing="$(count_existing "$channel" "$author")"
  if [ "$existing" -gt 0 ]; then
    [ "${RESET:-}" = 1 ] || fail "đã có $existing tin của kênh $channel và tin nhiễu, đặt RESET=1 để xóa (kèm VACUUM FULL) rồi seed lại"
    psql_nexus -c "delete from messaging.messages where channel_id = '$channel' or (author_id = '$author' and content like 'noise %')"
    psql_nexus -c "vacuum full messaging.messages"
  fi
  echo "Chèn $COUNT tin vào kênh $channel và $NOISE_CHANNELS x $NOISE_PER_CHANNEL tin nhiễu"
  time insert_messages "$channel" "$author" "$COUNT" "$NOISE_CHANNELS" "$NOISE_PER_CHANNEL"
  mid="$(mid_message_id "$channel" $((COUNT / 2)))"
  [ -n "$mid" ] || fail "không tìm được tin thứ $((COUNT / 2))"
  jq --arg m "$mid" '.midMessageId = $m' "$STATE" >"$STATE.tmp"
  mv "$STATE.tmp" "$STATE"
  echo "Đã seed $(count_messages "$channel") tin kênh đo, $(count_noise "$author") tin nhiễu, midMessageId=$mid"
}

main
