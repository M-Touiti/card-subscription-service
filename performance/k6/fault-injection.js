// Steady write load while the external API misbehaves for a fixed window.
//   FAULT=error  the stub answers HTTP 500 immediately
//   FAULT=hang   the stub answers after 12 s, past the adapter's 3 s read timeout
// Emits one console line per second of circuit-breaker state and one per request.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { BASE, JSON_HEADERS, STUB, TREND_STATS, subscribe } from './lib.js';

const RATE = parseInt(__ENV.RATE || '50', 10);
const BEFORE = parseInt(__ENV.BEFORE_S || '30', 10);
const FAULT_S = parseInt(__ENV.FAULT_S || '40', 10);
const AFTER = parseInt(__ENV.AFTER_S || '50', 10);
const FAULT = __ENV.FAULT || 'error';
const BASE_DELAY_MS = parseInt(__ENV.BASE_DELAY_MS || '50', 10);
const TOTAL = BEFORE + FAULT_S + AFTER;
const RUN = __ENV.RUN_ID || `f${Date.now()}`;
const OVERRIDE_ID = '5a1f0c6e-0000-4000-8000-0000000000ff';

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${TOTAL}s`,
      preAllocatedVUs: 100,
      maxVUs: 1500,
      gracefulStop: '45s',
      exec: 'load',
    },
    health: {
      executor: 'constant-arrival-rate',
      rate: 1,
      timeUnit: '1s',
      duration: `${TOTAL}s`,
      preAllocatedVUs: 2,
      maxVUs: 4,
      exec: 'health',
    },
    controller: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: `${TOTAL + 5}s`,
      exec: 'controller',
    },
  },
};

function inject() {
  if (FAULT === 'hang') {
    http.post(`${STUB}/__admin/settings`, JSON.stringify({ fixedDelay: 12000 }), JSON_HEADERS);
    return;
  }
  http.post(`${STUB}/__admin/mappings`, JSON.stringify({
    id: OVERRIDE_ID,
    priority: 1,
    request: { method: 'POST', urlPath: '/api/v1/subscribe-new-card' },
    response: { status: 500, body: 'injected fault', fixedDelayMilliseconds: 0 },
  }), JSON_HEADERS);
}

function clear() {
  http.post(`${STUB}/__admin/settings`, JSON.stringify({ fixedDelay: BASE_DELAY_MS }), JSON_HEADERS);
  http.del(`${STUB}/__admin/mappings/${OVERRIDE_ID}`);
}

export function setup() {
  clear();
  return { startedAt: Date.now() };
}

export function controller() {
  sleep(BEFORE);
  inject();
  console.log(JSON.stringify({ kind: 'event', event: 'fault_on', t: Date.now() }));
  sleep(FAULT_S);
  clear();
  console.log(JSON.stringify({ kind: 'event', event: 'fault_off', t: Date.now() }));
}

export function load() {
  const i = exec.scenario.iterationInTest;
  const started = Date.now();
  const res = subscribe(`${RUN}-${i}`, `user-${i % 1000}`);
  let outcome = `http_${res.status}`;
  if (res.status === 201) outcome = res.json('status');
  console.log(JSON.stringify({
    kind: 'req', t: started, ms: Math.round(res.timings.duration), outcome,
  }));
}

// Reads breaker state from both Prometheus and /actuator/health, which should agree.
export function health() {
  const metrics = http.get(`${BASE}/actuator/prometheus`, { tags: { name: 'poller' }, timeout: '2s' });
  const active = metrics.status === 200
    ? metrics.body.match(/^resilience4j_circuitbreaker_state\{[^}]*state="([a-z_]+)",?\} 1\.0/m)
    : null;
  const probe = http.get(`${BASE}/actuator/health`, { tags: { name: 'poller' }, timeout: '2s' });
  console.log(JSON.stringify({
    kind: 'health',
    t: Date.now(),
    state: active ? active[1] : 'no_response',
    http: probe.status,
    overall: probe.status === 200 ? probe.json('status') : null,
    reported: probe.status === 200
      ? probe.json('components.circuitBreakers.details.externalCardApi.details.state')
      : null,
  }));
}
