// POST /card-subscriptions with a unique card per iteration: the full write path
// (idempotency lookup, PENDING insert, external call, status update).
import exec from 'k6/execution';
import { DNS, TREND_STATS, loadScenario, pollerScenario, sampleApp, subscribe } from './lib.js';

const RUN = __ENV.RUN_ID || `w${Date.now()}`;

export const options = {
  dns: DNS,
  summaryTrendStats: TREND_STATS,
  scenarios: {
    load: Object.assign(loadScenario(), { exec: 'write' }),
    poller: pollerScenario,
  },
};

export function write() {
  const i = exec.scenario.iterationInTest;
  subscribe(`${RUN}-${i}`, `user-${i % 1000}`);
}

export const poll = sampleApp;
