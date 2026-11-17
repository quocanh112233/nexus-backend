#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K6_IMAGE="grafana/k6:2.4.0"
PROJECT="${COMPOSE_PROJECT:-}"

# Chạy k6 trong network của stack: tham số đầu là file .js trong infra/perf/k6 (kèm -e K=V), khác thì chuyển thẳng cho k6; trả exit code của k6
main() {
  [ -n "$PROJECT" ] || { echo "FAIL: phải đặt COMPOSE_PROJECT=<project compose đang chạy stack>" >&2; return 1; }
  command -v docker >/dev/null || { echo "FAIL: thiếu docker" >&2; return 1; }
  [ "$#" -gt 0 ] || { echo "FAIL: dùng infra/perf/run.sh <script.js> [-e K=V ...] hoặc <tham số k6>" >&2; return 1; }
  mkdir -p "$ROOT/infra/perf/.state"
  local docker_args=(run --rm --user "$(id -u):$(id -g)" --network "${PROJECT}_default"
    -v "$ROOT/infra/perf:/perf:ro" -v "$ROOT/infra/perf/.state:/out" "$K6_IMAGE")
  case "$1" in
    *.js)
      local script="$1"
      shift
      [ -f "$ROOT/infra/perf/k6/$script" ] || { echo "FAIL: không có infra/perf/k6/$script" >&2; return 1; }
      docker "${docker_args[@]}" run --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)" \
        --summary-export "/out/${script%.js}.summary.json" \
        -e BASE_URL=http://api-gateway:8080 -e WS_URL=ws://api-gateway:8080/ws "$@" "/perf/k6/$script"
      ;;
    *)
      docker "${docker_args[@]}" "$@"
      ;;
  esac
}

main "$@"
