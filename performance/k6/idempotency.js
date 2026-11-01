// Checks the "safe to call multiple times with the same cardId" guarantee two ways:
//   race    PARALLEL identical POSTs per card, sent at the same moment
//   retry   a card fails, is retried after recovery, then is requested once more
// Row counts per card are read from Postgres afterwards by the suite script.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE, JSON_HEADERS, STUB, TREND_STATS } from './lib.js';

const RUN = __ENV.RUN_ID || `i${Date.now()}`;
const CARDS = parseInt(__ENV.CARDS || '200', 10);
const PARALLEL = parseInt(__ENV.PARALLEL || '5', 10);
const RETRY_CARDS = parseInt(__ENV.RETRY_CARDS || '20', 10);
const BASE_DELAY_MS = parseInt(__ENV.BASE_DELAY_MS || '50', 10);
const OVERRIDE_ID = '5a1f0c6e-0000-4000-8000-0000000000fe';

const raceStatus = new Counter('race_status');
const retryStatus = new Counter('retry_status');

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    race: {
      executor: 'shared-iterations',
      vus: 4,
      iterations: CARDS,
      maxDuration: '3m',
      exec: 'race',
    },
    retry: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      startTime: '60s',
      maxDuration: '2m',
      exec: 'retry',
    },
  },
};

function body(cardId) {
  return JSON.stringify({ cardId, userId: 'user-1' });
}

function post(cardId) {
  return http.post(`${BASE}/api/v1/card-subscriptions`, body(cardId), JSON_HEADERS);
}

export function race() {
  const cardId = `${RUN}-race-${exec.scenario.iterationInTest}`;
  const params = Object.assign({ tags: { name: 'race' } }, JSON_HEADERS);
  const responses = http.batch(Array.from({ length: PARALLEL }, () => (
    ['POST', `${BASE}/api/v1/card-subscriptions`, body(cardId), params]
  )));
  responses.forEach((r) => raceStatus.add(1, { status: String(r.status) }));
  console.log(JSON.stringify({
    kind: 'race', cardId, statuses: responses.map((r) => r.status).sort().join(','),
  }));
}

export function retry() {
  http.post(`${STUB}/__admin/mappings`, JSON.stringify({
    id: OVERRIDE_ID,
    priority: 1,
    request: { method: 'POST', urlPath: '/api/v1/subscribe-new-card' },
    response: { status: 500, body: 'injected fault' },
  }), JSON_HEADERS);

  const cards = Array.from({ length: RETRY_CARDS }, (_, i) => `${RUN}-retry-${i}`);
  const steps = { first_during_outage: [], retry_after_recovery: [], third_call: [], get_by_card: [] };
  cards.forEach((c) => {
    const r = post(c);
    steps.first_during_outage.push(`${r.status}:${r.status === 201 ? r.json('status') : ''}`);
  });

  http.del(`${STUB}/__admin/mappings/${OVERRIDE_ID}`);
  http.post(`${STUB}/__admin/settings`, JSON.stringify({ fixedDelay: BASE_DELAY_MS }), JSON_HEADERS);
  sleep(15); // past wait-duration-in-open-state, so the breaker is half-open or closed

  cards.forEach((c) => {
    const r = post(c);
    steps.retry_after_recovery.push(`${r.status}:${r.status === 201 ? r.json('status') : ''}`);
    retryStatus.add(1, { step: 'retry', status: String(r.status) });
  });
  cards.forEach((c) => {
    const r = post(c);
    steps.third_call.push(`${r.status}:${r.status === 201 ? r.json('status') : ''}`);
    retryStatus.add(1, { step: 'third', status: String(r.status) });
  });
  cards.forEach((c) => {
    const r = http.get(`${BASE}/api/v1/card-subscriptions/card/${c}`);
    steps.get_by_card.push(`${r.status}`);
  });

  const tally = (values) => values.reduce((acc, v) => {
    acc[v] = (acc[v] || 0) + 1;
    return acc;
  }, {});
  console.log(JSON.stringify({
    kind: 'retry',
    first_during_outage: tally(steps.first_during_outage),
    retry_after_recovery: tally(steps.retry_after_recovery),
    third_call: tally(steps.third_call),
    get_by_card: tally(steps.get_by_card),
  }));
}
