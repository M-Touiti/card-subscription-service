import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

export const BASE = __ENV.BASE_URL || 'http://app:8080';
export const STUB = __ENV.STUB_URL || 'http://stub:8080';
export const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

// Round-robin across compose replicas; each new connection re-resolves "app".
export const DNS = { ttl: '0', select: 'roundRobin', policy: 'any' };
export const TREND_STATS = ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];

// Business metrics exclude the metrics poller, so k6's built-in http_reqs is not used.
export const ok = new Counter('business_ok');
export const serverError = new Counter('business_5xx');
export const otherStatus = new Counter('business_other_status');
export const subscribed = new Counter('outcome_subscribed');
export const failed = new Counter('outcome_failed');
export const latency = new Trend('business_latency', true);

const hikariActive = new Trend('hikari_active');
const hikariPending = new Trend('hikari_pending');
const appCpu = new Trend('app_cpu_usage');

export function record(res) {
  latency.add(res.timings.duration);
  if (res.status >= 200 && res.status < 300) ok.add(1);
  else if (res.status >= 500 || res.status === 0) serverError.add(1);
  else otherStatus.add(1);
}

export function subscribe(cardId, userId) {
  const res = http.post(
    `${BASE}/api/v1/card-subscriptions`,
    JSON.stringify({ cardId, userId }),
    Object.assign({ tags: { name: 'subscribe' } }, JSON_HEADERS),
  );
  record(res);
  if (res.status === 201) {
    const status = res.json('status');
    if (status === 'SUBSCRIBED') subscribed.add(1);
    else if (status === 'FAILED') failed.add(1);
  }
  return res;
}

function gauge(body, name) {
  const match = body.match(new RegExp(`^${name}\\{[^}]*\\} ([0-9.eE+-]+)`, 'm'));
  return match ? parseFloat(match[1]) : null;
}

// Samples pool occupancy and CPU from the service's own Prometheus endpoint.
export function sampleApp() {
  const res = http.get(`${BASE}/actuator/prometheus`, { tags: { name: 'poller' } });
  if (res.status !== 200) return;
  const active = gauge(res.body, 'hikaricp_connections_active');
  const pending = gauge(res.body, 'hikaricp_connections_pending');
  const cpu = gauge(res.body, 'process_cpu_usage');
  if (active !== null) hikariActive.add(active);
  if (pending !== null) hikariPending.add(pending);
  if (cpu !== null) appCpu.add(cpu);
}

export const pollerScenario = {
  executor: 'constant-arrival-rate',
  rate: 1,
  timeUnit: '2s',
  duration: __ENV.DURATION || '60s',
  preAllocatedVUs: 2,
  maxVUs: 4,
  exec: 'poll',
};

// Open model (fixed arrival rate, shows queueing) when RATE is set;
// otherwise closed model (fixed concurrency, shows maximum throughput).
export function loadScenario() {
  const duration = __ENV.DURATION || '60s';
  if (__ENV.RATE) {
    return {
      executor: 'constant-arrival-rate',
      rate: parseInt(__ENV.RATE, 10),
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Math.min(parseInt(__ENV.RATE, 10), 300),
      maxVUs: 1500,
      gracefulStop: '15s',
    };
  }
  return {
    executor: 'constant-vus',
    vus: parseInt(__ENV.VUS || '100', 10),
    duration,
    gracefulStop: '15s',
  };
}
