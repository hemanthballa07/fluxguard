// Steady load on /api/search (sliding window: 100 req / 60 s per client)
// 200 VUs across 20 client IDs = each client sees ~10 rps > 1.66 rps limit.
// Produces a realistic allow/deny mix to measure both code paths.
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const rate429 = new Rate('http_429');
const rate200 = new Rate('http_200');

export const options = {
  scenarios: {
    steady_search: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 100,
      maxVUs: 400,
    },
  },
  thresholds: {
    'http_req_duration{expected_response:true}': ['p(95)<50', 'p(99)<100'],
  },
};

const BASE = __ENV.TARGET_URL || 'http://localhost:8080';
const CLIENT_POOL = 20;

export default function () {
  const clientId = `search-client-${__VU % CLIENT_POOL}`;
  const res = http.get(`${BASE}/api/search`, {
    headers: { 'X-Client-ID': clientId },
    tags: { endpoint: '/api/search' },
  });
  rate429.add(res.status === 429);
  rate200.add(res.status === 200);
  check(res, { 'is 200 or 429': (r) => r.status === 200 || r.status === 429 });
}
