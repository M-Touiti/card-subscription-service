// Direct load on the external-API stub, bypassing the service.
import http from 'k6/http';
import { JSON_HEADERS, STUB, TREND_STATS, record } from './lib.js';

export const options = { summaryTrendStats: TREND_STATS, vus: 200, duration: '30s' };

export default function () {
  record(http.post(`${STUB}/api/v1/subscribe-new-card`, '{}', JSON_HEADERS));
}
