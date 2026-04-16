import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 10  },
    { duration: '10s', target: 500 },
    { duration: '30s', target: 500 },
    { duration: '10s', target: 10  },
    { duration: '30s', target: 10  },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
  },
};

export default function () {
  const res = http.get('http://localhost:8080/api/v1/health', {
    headers: { 'X-Client-ID': `burst-client-${__VU}` },
  });
  check(res, { 'is 200 or 429': (r) => r.status === 200 || r.status === 429 });
  sleep(0.005);
}
