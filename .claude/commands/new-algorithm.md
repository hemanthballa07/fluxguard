Scaffold a new rate limiting algorithm: $ARGUMENTS

1. Create /src/main/java/com/sentinelrate/algorithm/$ARGUMENTS.java implementing RateLimitAlgorithm
2. Create matching Lua script in /src/main/resources/lua/
3. Create unit test with boundary conditions: exact limit, limit+1, burst
4. Register in AlgorithmFactory
5. Update PROJECT_STATE.md files changed section
6. Run: mvn test -Dtest=$ARGUMENTSTest
7. Do not mark done until all tests pass
