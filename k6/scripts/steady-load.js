import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 100 },
    { duration: '2m',  target: 100 },
    { duration: '30s', target: 0   },
  ],
  thresholds: {
    http_req_duration: ['p(95)<200'],
    http_req_failed:   ['rate<0.01'],
  },
};

export default function () {
  const res = http.get('http://localhost:8080/api/v1/health', {
    headers: { 'X-Client-ID': `steady-client-${__VU}` },
  });
  check(res, { 'is 200 or 429': (r) => r.status === 200 || r.status === 429 });
  sleep(0.01);
}
