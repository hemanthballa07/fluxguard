#!/bin/bash
TS=$(date +%Y%m%d-%H%M%S)
RESULTS="$(dirname "$0")/../results"
mkdir -p $RESULTS
echo "=== SentinelRate Benchmark: $TS ==="
k6 run --out json=$RESULTS/$TS-steady.json "$(dirname "$0")/steady-load.js"
k6 run --out json=$RESULTS/$TS-burst.json "$(dirname "$0")/burst-load.js"
echo "Results saved. Run /update-state to log in PROJECT_STATE.md"
