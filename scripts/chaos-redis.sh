#!/bin/bash
#
# chaos-redis.sh — Verifies fail-open behaviour when Redis is unavailable.
#
# IMPORTANT: this test MUST hit a rate-limited endpoint, otherwise the request
# never reaches Redis and the test proves nothing. /api/search is limited by the
# sliding-window config in RateLimitConfiguration, so a Redis outage exercises the
# fail-open path in RateLimitFilter (kill-switch read, config read, flag read, and
# the Lua decision are all expected to fail open and return 200, not 500).
#
# The app must be running with the `benchmark` profile so /api/search has a handler:
#   REDIS_HOST=localhost SPRING_PROFILES_ACTIVE=benchmark mvn spring-boot:run
#
set -uo pipefail

COMPOSE="$(dirname "$0")/../docker/docker-compose.yml"
BASE_URL="${TARGET_URL:-http://localhost:8080}"
ENDPOINT="${CHAOS_ENDPOINT:-/api/search}"   # MUST be a rate-limited endpoint
REQUESTS="${CHAOS_REQUESTS:-10}"

echo "=== Chaos Test: fail-open under Redis outage ==="
echo "target   : ${BASE_URL}${ENDPOINT}"
echo

echo "--- Stopping Redis ---"
docker-compose -f "$COMPOSE" stop redis

echo "Sending ${REQUESTS} requests to a rate-limited endpoint (all should fail open -> 200)..."
PASS=true
for i in $(seq 1 "$REQUESTS"); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    "${BASE_URL}${ENDPOINT}" \
    -H "X-Client-ID: chaos-test-$i")
  echo "Request $i: HTTP $STATUS"
  # 200 = failed open and allowed. Anything else (esp. 500/503) means fail-open broke.
  if [ "$STATUS" != "200" ]; then PASS=false; fi
done

echo
echo "--- Restarting Redis ---"
docker-compose -f "$COMPOSE" start redis

echo
if [ "$PASS" = true ]; then
  echo "PASS: every request returned 200 during the Redis outage (fail-open works on a limited endpoint)"
  exit 0
else
  echo "FAIL: at least one request did not return 200 -- fail-open is incomplete (check the non-decision Redis reads in RateLimitFilter)"
  exit 1
fi
