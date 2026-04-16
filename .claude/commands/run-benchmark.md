Run k6 benchmark and capture results.

1. Ensure service is running: docker-compose -f docker/docker-compose.yml up -d && mvn spring-boot:run
2. Run: ./k6/scripts/run-benchmark.sh
3. Capture p50, p95, p99 and max RPS from output
4. Add row to Benchmark results log in PROJECT_STATE.md
5. Update README.md benchmark table with new numbers
6. Save raw output to /k6/results/[YYYYMMDD]-[scenario].txt
7. Report: "Benchmark done. p95: Xms, RPS: Y. README and state updated."
