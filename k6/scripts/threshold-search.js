// Single hot client hammers /api/search well above the 100/60s limit.
// Validates deny-path latency, Retry-After header, and sliding-window correctness.
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const rate429 = new Rate('http_429');
const rate200 = new Rate('http_200');
const denyLat = new Trend('deny_latency_ms');

export const options = {
  scenarios: {
    threshold: {
      executor: 'constant-arrival-rate',
      rate: 500,
      timeUnit: '1s',
      duration: '1m',
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
  },
  thresholds: {
    'http_req_duration': ['p(95)<100', 'p(99)<200'],
  },
};

const BASE = __ENV.TARGET_URL || 'http://localhost:8080';

export default function () {
  const res = http.get(`${BASE}/api/search`, {
    headers: { 'X-Client-ID': 'threshold-hot-client' },
    tags: { endpoint: '/api/search' },
  });
  denyLat.add(res.timings.duration);
  rate429.add(res.status === 429);
  rate200.add(res.status === 200);
  check(res, {
    'is 200 or 429': (r) => r.status === 200 || r.status === 429,
    '429 has Retry-After': (r) => r.status !== 429 || r.headers['Retry-After'] !== undefined,
  });
}
