// Burst load on /api/ingest to exercise token-bucket cap and refill.
// Ramps to 500 rps to drain the 50-token cap, then drops to measure refill recovery.
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const rate429 = new Rate('http_429');
const rate200 = new Rate('http_200');
const burstLat = new Trend('burst_latency_ms');

export const options = {
  scenarios: {
    burst: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 500,
      stages: [
        { target: 500, duration: '10s' },
        { target: 500, duration: '30s' },
        { target: 10,  duration: '10s' },
        { target: 10,  duration: '30s' },
      ],
    },
  },
  thresholds: {
    'http_req_duration{expected_response:true}': ['p(95)<100', 'p(99)<200'],
  },
};

const BASE = __ENV.TARGET_URL || 'http://localhost:8080';
const CLIENT_POOL = 10;

export default function () {
  const clientId = `burst-client-${__VU % CLIENT_POOL}`;
  const res = http.post(`${BASE}/api/ingest`, null, {
    headers: { 'X-Client-ID': clientId },
    tags: { endpoint: '/api/ingest' },
  });
  burstLat.add(res.timings.duration);
  rate429.add(res.status === 429);
  rate200.add(res.status === 200);
  check(res, { 'is 200 or 429': (r) => r.status === 200 || r.status === 429 });
}
