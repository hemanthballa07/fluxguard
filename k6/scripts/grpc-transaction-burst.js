// grpc-transaction-burst.js — k6 gRPC stress for the fluxguard rate-limit surface.
//
// Proves the token-bucket limiter on POLICY_TRANSACTION bites under load and
// recovers after refill:
//   1) `drain` scenario: many concurrent CheckLimit calls for ONE hot subject
//      empty the bucket -> a wave of DECISION_DENY (rl_denied > 0).
//   2) `recover` scenario: starts after a refill gap, same subject -> the bucket
//      has refilled, so calls are allowed again (rl_recovered_allowed > 0).
//
// Run from the repo root against a LIVE fluxguard gRPC server (default :9099):
//   FLUXGUARD_GRPC=localhost:9099 ./k6/scripts/run-grpc-benchmark.sh
//
// Requires the TRANSACTION policy to use its default (capacity 20, refill 5/s) or
// a tuned config; the thresholds below assume a bucket small enough to drain.

import grpc from 'k6/net/grpc';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const client = new grpc.Client();
// importPaths resolve relative to THIS script's dir (k6/scripts), so step up to the
// repo root's proto tree; the proto has no imports.
client.load(['../../src/main/proto'], 'fluxguard/ratelimit/v1/ratelimit.proto');

const allowed = new Counter('rl_allowed');
const denied = new Counter('rl_denied');
const recoveredAllowed = new Counter('rl_recovered_allowed');

const ADDR = __ENV.FLUXGUARD_GRPC || 'localhost:9099';
const SUBJECT = __ENV.SUBJECT || 'k6-hot-subject';

export const options = {
  scenarios: {
    drain: {
      executor: 'shared-iterations',
      vus: 20,
      iterations: 400,
      maxDuration: '15s',
      exec: 'drain',
    },
    recover: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 10,
      startTime: '20s', // after the drain + a refill gap
      maxDuration: '10s',
      exec: 'recover',
    },
  },
  thresholds: {
    // The limiter must actually deny during the burst, and recover afterwards.
    rl_denied: ['count>0'],
    rl_recovered_allowed: ['count>0'],
    grpc_req_duration: ['p(95)<100'],
  },
};

let connected = false;

function ensureConnected() {
  if (!connected) {
    client.connect(ADDR, { plaintext: true });
    connected = true;
  }
}

function callCheckLimit(tag) {
  const resp = client.invoke('fluxguard.ratelimit.v1.RateLimit/CheckLimit', {
    request_id: `k6-${tag}-${__VU}-${__ITER}`,
    policy: 'POLICY_TRANSACTION',
    subject: SUBJECT, // one hot subject so the bucket drains
    client_ip: '127.0.0.1',
    idempotency_key: '', // no dedup — every call competes for a token
  });
  check(resp, { 'status OK': (r) => r && r.status === grpc.StatusOK });
  return resp && resp.message ? resp.message.decision : undefined;
}

export function drain() {
  ensureConnected();
  const decision = callCheckLimit('drain');
  if (decision === 'DECISION_ALLOW') {
    allowed.add(1);
  } else if (decision === 'DECISION_DENY') {
    denied.add(1);
  }
}

export function recover() {
  ensureConnected();
  const decision = callCheckLimit('recover');
  if (decision === 'DECISION_ALLOW') {
    recoveredAllowed.add(1);
  }
}

export function teardown() {
  client.close();
}
