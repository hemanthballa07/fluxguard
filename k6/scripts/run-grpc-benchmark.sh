#!/usr/bin/env bash
# run-grpc-benchmark.sh — run the gRPC rate-limit burst stress and capture results.
#
# Requires a LIVE fluxguard gRPC server (default localhost:9099) backed by Redis.
# Bring fluxguard up first, e.g.:
#   docker-compose -f docker/docker-compose.yml up -d   # Redis + Jaeger + Grafana
#   OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 mvn spring-boot:run   # app :8080 + gRPC :9099
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

ADDR="${FLUXGUARD_GRPC:-localhost:9099}"
HOST="${ADDR%%:*}"
PORT="${ADDR##*:}"
RESULTS_DIR="k6/results"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="${RESULTS_DIR}/grpc-transaction-burst-${STAMP}.json"

mkdir -p "$RESULTS_DIR"

echo "=== fluxguard gRPC rate-limit burst ==="
echo "target : ${ADDR}"
if ! nc -z "$HOST" "$PORT" 2>/dev/null; then
  echo "WARNING: nothing is listening on ${ADDR} — start the fluxguard gRPC server first." >&2
fi
echo

FLUXGUARD_GRPC="$ADDR" k6 run \
  --summary-export "$OUT" \
  k6/scripts/grpc-transaction-burst.js
STATUS=$?

echo
if [ "$STATUS" -eq 0 ]; then
  echo "PASS: thresholds met (limiter denied under load and recovered). Summary -> $OUT"
else
  echo "FAIL: k6 thresholds not met (exit $STATUS). Summary -> $OUT" >&2
fi
exit "$STATUS"
