// GET by primary key (LOOKUP=id) or by card_id (LOOKUP=card) against seeded rows.
import http from 'k6/http';
import { BASE, DNS, TREND_STATS, loadScenario, pollerScenario, record, sampleApp } from './lib.js';

const ROWS = parseInt(__ENV.ROWS || '10000', 10);
const LOOKUP = __ENV.LOOKUP || 'id';

export const options = {
  dns: DNS,
  summaryTrendStats: TREND_STATS,
  scenarios: {
    load: Object.assign(loadScenario(), { exec: 'read' }),
    poller: pollerScenario,
  },
};

export function read() {
  const n = 1 + Math.floor(Math.random() * ROWS);
  const path = LOOKUP === 'card' ? `card/seed-card-${n}` : `id-${n}`;
  record(http.get(`${BASE}/api/v1/card-subscriptions/${path}`, { tags: { name: 'read' } }));
}

export const poll = sampleApp;
