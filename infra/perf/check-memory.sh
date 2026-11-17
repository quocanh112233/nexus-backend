#!/bin/bash
set -euo pipefail
export LC_ALL=C
# Đổi chuỗi dung lượng của docker stats (B, KiB, MiB, GiB, kB, MB, GB) về MiB
to_mib() {
  awk -v s="$1" 'BEGIN {
    match(s, /[A-Za-z]+$/); unit = substr(s, RSTART); v = substr(s, 1, RSTART - 1) + 0
    if (unit == "B") v /= 1048576
    else if (unit == "KiB") v /= 1024
    else if (unit == "GiB") v *= 1024
    else if (unit == "kB") v *= 1000 / 1048576
    else if (unit == "MB") v *= 1000000 / 1048576
    else if (unit == "GB") v *= 1000000000 / 1048576
    printf "%.2f", v
  }'
}

# In bảng container/usage/limit của một project compose và dòng TOTAL; thoát 1 nếu tổng usage > 6 GiB, có container không ở trạng thái running, OOMKilled hoặc RestartCount > 0; cần docker và awk
main() {
  local project="${1:-}" limit_mib=6144 fail=0 total=0 name usage limit state oom restarts stats_out inspect_out ids_out
  [ -n "$project" ] || { echo "FAIL: dùng infra/perf/check-memory.sh <compose-project>"; return 1; }
  ids_out="$(docker ps -aq --filter "label=com.docker.compose.project=$project")"
  [ -n "$ids_out" ] || { echo "FAIL: project $project không có container nào"; return 1; }
  local ids
  mapfile -t ids <<<"$ids_out"
  stats_out="$(docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' "${ids[@]}")"
  inspect_out="$(docker inspect --format '{{.Name}} {{.State.Status}} {{.State.OOMKilled}} {{.RestartCount}}' "${ids[@]}")"
  printf '%-40s %12s %12s\n' container usage limit
  while read -r name usage _ limit; do
    printf '%-40s %12s %12s\n' "$name" "$usage" "$limit"
    total="$(awk -v t="$total" -v u="$(to_mib "$usage")" 'BEGIN { printf "%.2f", t + u }')"
  done <<<"$stats_out"
  while read -r name state oom restarts; do
    if [ "$state" != running ] || [ "$oom" != false ] || [ "$restarts" != 0 ]; then
      echo "FAIL: $name state=$state OOMKilled=$oom RestartCount=$restarts"
      fail=1
    fi
  done <<<"$inspect_out"
  awk -v t="$total" 'BEGIN { printf "TOTAL %.2f GiB\n", t / 1024 }'
  awk -v t="$total" -v l="$limit_mib" 'BEGIN { exit !(t > l) }' && { echo "FAIL: tổng usage vượt 6 GiB"; fail=1; }
  return "$fail"
}

main "$@"
