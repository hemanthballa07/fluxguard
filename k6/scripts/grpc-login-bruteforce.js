// grpc-login-bruteforce.js — k6 gRPC brute-force scenario for the LOGIN policy.
//
// Simulates one attacker IP repeatedly attempting a login: each iteration does a
// CheckLimit(LOGIN) pre-check, then (the attempt "fails") a ReportLoginFailure.
// Once the IP-keyed failure window fills (default 5 / 300s), the pre-check flips
// to DECISION_DENY — the brute-force is throttled (login_denied > 0).
//
// Run from the repo root against a LIVE fluxguard gRPC server (LOGIN must be
// implemented, i.e. Phase 2):
//   FLUXGUARD_GRPC=localhost:9099 ATTACK_IP=203.0.113.7 \
//     k6 run k6/scripts/grpc-login-bruteforce.js
// Re-runs within the 300s window reuse the same window — pass a fresh ATTACK_IP.

import grpc from 'k6/net/grpc';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const client = new grpc.Client();
client.load(['../../src/main/proto'], 'fluxguard/ratelimit/v1/ratelimit.proto');

const loginAllowed = new Counter('login_allowed');
const loginDenied = new Counter('login_denied');

const ADDR = __ENV.FLUXGUARD_GRPC || 'localhost:9099';
const ATTACK_IP = __ENV.ATTACK_IP || '203.0.113.7';
const VICTIM = __ENV.VICTIM || 'victim-user';

export const options = {
  scenarios: {
    bruteforce: {
      executor: 'shared-iterations',
      vus: 1, // a single attacker IP, sequential attempts
      iterations: 30,
      maxDuration: '20s',
    },
  },
  thresholds: {
    // The brute-force IP must get blocked once the failure window fills.
    login_denied: ['count>0'],
    grpc_req_duration: ['p(95)<100'],
  },
};

let connected = false;

export default function () {
  if (!connected) {
    client.connect(ADDR, { plaintext: true });
    connected = true;
  }
  // 1. The attacker attempts a login — bankops' pre-handle CheckLimit(LOGIN).
  const pre = client.invoke('fluxguard.ratelimit.v1.RateLimit/CheckLimit', {
    request_id: `bf-${__ITER}`,
    policy: 'POLICY_LOGIN',
    subject: VICTIM, // audit only — the window is keyed on client_ip
    client_ip: ATTACK_IP,
    idempotency_key: '',
  });
  check(pre, { 'checkLimit OK': (r) => r && r.status === grpc.StatusOK });
  const decision = pre && pre.message ? pre.message.decision : undefined;
  if (decision === 'DECISION_ALLOW') {
    loginAllowed.add(1);
  } else if (decision === 'DECISION_DENY') {
    loginDenied.add(1);
  }
  // 2. The credentials are wrong (401) — bankops reports the failure post-auth.
  const rep = client.invoke('fluxguard.ratelimit.v1.RateLimit/ReportLoginFailure', {
    request_id: `bf-${__ITER}`,
    subject: VICTIM,
    client_ip: ATTACK_IP,
  });
  check(rep, { 'report OK': (r) => r && r.status === grpc.StatusOK });
}

export function teardown() {
  client.close();
}
