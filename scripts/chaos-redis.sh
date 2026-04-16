#!/bin/bash
echo "=== Chaos Test: Stopping Redis ==="
docker-compose -f "$(dirname "$0")/../docker/docker-compose.yml" stop redis
echo "Redis stopped. Sending 10 requests (all should return 200)..."
PASS=true
for i in {1..10}; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    http://localhost:8080/api/v1/health \
    -H "X-Client-ID: chaos-test-$i")
  echo "Request $i: HTTP $STATUS"
  if [ "$STATUS" != "200" ]; then PASS=false; fi
done
if [ "$PASS" = true ]; then
  echo "PASS: All requests returned 200 during Redis outage"
else
  echo "FAIL: Some requests did not return 200 — check fail-open logic"
fi
echo "Restart Redis: docker-compose -f docker/docker-compose.yml start redis"
