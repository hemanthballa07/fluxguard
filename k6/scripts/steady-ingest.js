// Steady load on /api/ingest (token bucket: capacity 50, refill 10/s per client)
// 100 rps across 10 clients = 10 rps/client = exactly the refill rate.
// After initial burst drains the bucket, measures steady-state allow path.
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const rate429 = new Rate('http_429');
const rate200 = new Rate('http_200');

export const options = {
  scenarios: {
    steady_ingest: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    'http_req_duration{expected_response:true}': ['p(95)<50', 'p(99)<100'],
  },
};

const BASE = __ENV.TARGET_URL || 'http://localhost:8080';
const CLIENT_POOL = 10;

export default function () {
  const clientId = `ingest-client-${__VU % CLIENT_POOL}`;
  const res = http.post(`${BASE}/api/ingest`, null, {
    headers: { 'X-Client-ID': clientId },
    tags: { endpoint: '/api/ingest' },
  });
  rate429.add(res.status === 429);
  rate200.add(res.status === 200);
  check(res, { 'is 200 or 429': (r) => r.status === 200 || r.status === 429 });
}
