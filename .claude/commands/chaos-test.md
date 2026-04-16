Run chaos test: kill Redis, verify fail-open.

1. Start stack: docker-compose -f docker/docker-compose.yml up -d && mvn spring-boot:run
2. Run: ./scripts/chaos-redis.sh
3. Verify all 10 requests return HTTP 200 (not 500)
4. Verify logs contain: "Redis unavailable — failing open"
5. Verify Prometheus: redis_failopen_total > 0
6. Restart Redis: docker-compose -f docker/docker-compose.yml start redis
7. Wait 30s, send requests, verify rate limiting resumes
8. Report: PASS or FAIL with log evidence
