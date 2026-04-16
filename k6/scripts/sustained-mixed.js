// 5-minute soak test mixing both endpoints — for dashboard screenshotting
// and p99 drift detection under sustained load.
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const rate429 = new Rate('http_429');
const rate200 = new Rate('http_200');

export const options = {
  scenarios: {
    mixed_search: {
      executor: 'constant-arrival-rate',
      exec: 'hitSearch',
      rate: 150,
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 80,
      maxVUs: 300,
    },
    mixed_ingest: {
      executor: 'constant-arrival-rate',
      exec: 'hitIngest',
      rate: 80,
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 40,
      maxVUs: 200,
    },
  },
  thresholds: {
    'http_req_duration{endpoint:/api/search}': ['p(95)<50', 'p(99)<100'],
    'http_req_duration{endpoint:/api/ingest}': ['p(95)<50', 'p(99)<100'],
  },
};

const BASE = __ENV.TARGET_URL || 'http://localhost:8080';

export function hitSearch() {
  const clientId = `mixed-search-${__VU % 25}`;
  const res = http.get(`${BASE}/api/search`, {
    headers: { 'X-Client-ID': clientId },
    tags: { endpoint: '/api/search' },
  });
  rate429.add(res.status === 429);
  rate200.add(res.status === 200);
  check(res, { 'is 200 or 429': (r) => r.status === 200 || r.status === 429 });
}

export function hitIngest() {
  const clientId = `mixed-ingest-${__VU % 15}`;
  const res = http.post(`${BASE}/api/ingest`, null, {
    headers: { 'X-Client-ID': clientId },
    tags: { endpoint: '/api/ingest' },
  });
  rate429.add(res.status === 429);
  rate200.add(res.status === 200);
  check(res, { 'is 200 or 429': (r) => r.status === 200 || r.status === 429 });
}
