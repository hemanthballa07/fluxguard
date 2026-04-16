#!/usr/bin/env bash
#
# run-benchmark.sh — Executes the FluxGuard k6 benchmark suite.
#
# Usage:
#   TARGET_URL=http://localhost:8080 SAMPLER_LABEL=always_on ./run-benchmark.sh
#   TARGET_URL=http://localhost:8080 SAMPLER_LABEL=sampled_1pct ./run-benchmark.sh
#
# Output lands under k6/results/<ts>-<sampler>/:
#   *.summary.json  — k6 end-of-run summary (thresholds, counts, percentiles)
#   *.full.json     — full sample stream (for ad-hoc analysis)
#   *.stdout.log    — raw k6 stdout
#
set -euo pipefail

command -v k6 >/dev/null 2>&1 || {
  echo "k6 not found. Install: brew install k6  or  https://k6.io/docs/get-started/installation/"
  exit 1
}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_ROOT="${SCRIPT_DIR}/../results"
TS="$(date +%Y%m%d-%H%M%S)"
SAMPLER="${SAMPLER_LABEL:-unlabeled}"
OUT_DIR="${RESULTS_ROOT}/${TS}-${SAMPLER}"
mkdir -p "${OUT_DIR}"

TARGET_URL="${TARGET_URL:-http://localhost:8080}"
export TARGET_URL

SCENARIOS=(
  "steady-search"
  "steady-ingest"
  "burst-ingest"
  "threshold-search"
  "sustained-mixed"
)

echo "=== FluxGuard benchmark run ==="
echo "ts        : ${TS}"
echo "target    : ${TARGET_URL}"
echo "sampler   : ${SAMPLER}"
echo "out dir   : ${OUT_DIR}"
echo

for name in "${SCENARIOS[@]}"; do
  script="${SCRIPT_DIR}/${name}.js"
  summary="${OUT_DIR}/${name}.summary.json"
  full="${OUT_DIR}/${name}.full.json"
  stdout_log="${OUT_DIR}/${name}.stdout.log"

  echo "--- ${name} ---"
  k6 run \
     --summary-export "${summary}" \
     --out "json=${full}" \
     "${script}" 2>&1 | tee "${stdout_log}" || echo "[WARN] ${name} exited non-zero (threshold breach or error)"
  echo
done

echo "All scenarios done."
echo "Results : ${OUT_DIR}"
echo "Next    : update README benchmark table and PROJECT_STATE.md"
