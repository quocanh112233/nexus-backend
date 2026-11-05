#!/bin/bash
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a
. ./.env
set +a

# Chạy SQL $3 trong db nexus bằng user $1 (mật khẩu $2), in cả stdout và stderr
sql() {
  docker compose exec -T postgres env PGPASSWORD="$2" psql -h postgres -U "$1" -d nexus -v ON_ERROR_STOP=1 -c "$3" 2>&1
}

# Kiểm messaging_svc bị chặn đọc community và identity_svc tự tạo/xóa được bảng trong schema của mình
verify() {
  local out
  out=$(sql messaging_svc "$MESSAGING_DB_PASSWORD" "SELECT 1 FROM community.members")
  if [[ "$out" != *"permission denied for schema community"* ]]; then
    echo "FAIL: messaging_svc không bị chặn khỏi community: $out" >&2
    return 1
  fi
  if ! out=$(sql identity_svc "$IDENTITY_DB_PASSWORD" "CREATE TABLE identity.tmp_check(id int); DROP TABLE identity.tmp_check;"); then
    echo "FAIL: identity_svc không tạo/xóa được bảng trong identity: $out" >&2
    return 1
  fi
  echo "OK: cách ly schema đúng"
}
verify
