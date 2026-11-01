# ADR 0004: Circuit breaker that records failures as `FAILED`

**Status:** Accepted
**Date:** 2026-05-30, revised 2026-11-01 after load testing

## Context

Every subscription depends on a third-party API. While that API is down, continuing to call it wastes time on requests that will fail and adds pressure to a struggling dependency.

The original configuration handled fast errors well but not hangs. With a 10 s read timeout and no slow-call rule, a hanging API took about 11 s to open the breaker, the slowest requests took almost 20 s, and `/actuator/health` failed its probe for about 8 s (F6). Breaker state was also missing from `/actuator/health` (F7). See the [load-test findings](../../performance/README.md#findings).

## Decision

Annotate `ExternalCardApiAdapter.subscribeCard()` with Resilience4j `@CircuitBreaker`:

- A sliding window of 10 calls, a minimum of 5 calls, and a 50% failure threshold.
- A 2 s slow-call threshold at a 50% rate, so a hang opens the breaker sooner.
- Open for 10 s, then half-open with 3 trial calls.
- A fallback that returns `false`, which the service saves as a `FAILED` subscription for the retry job ([ADR 0003](0003-claim-the-card-then-call-the-external-api-outside-a-transaction.md)).
- `RestTemplate` timeouts of 2 s to connect and 3 s to read.
- State reported in `/actuator/health` with `allow-health-indicator-to-fail: false`. The Docker `HEALTHCHECK` uses this endpoint, and a partner outage should not get healthy replicas restarted.

The breaker lives in the adapter so the application layer sees only a port returning success or failure.

## Consequences

**Benefits**

- With the API returning 500s, the breaker opened 1 s into the fault and closed about 1 s after recovery. Median response time fell from 60 ms to 8 ms during the fault.
- With the API hanging, the breaker opened 4 s into the fault instead of about 11 s, and the slowest request took 3.0 s instead of 19.9 s. Dropped requests fell from 204 to 26.
- `/actuator/health` returned 200 throughout both fault tests and reported the true breaker state.
- No 5xx responses in either fault test.

**Costs**

- **Recovery from a hang can lag.** The breaker closed about 13 s after the fault ended in the test, because it was waiting out its open period. 615 requests in the next 15 s were recorded `FAILED`, and the retry job then recovered them.
- **The first seconds of a hang are still slow.** Median response time was 3.0 s for the first 5 s, the read timeout.
- **The timeouts assume a fast partner.** Calls over 2 s count as slow, so these values must be tuned to the real API.
- **Health stays `UP` during an outage.** Alerts must use the breaker state, not the overall status.
- **The fallback hides the cause.** All exceptions, including client errors, lead to the same `FAILED` message, and client errors are retried too.
- **Each replica has its own breaker,** so replicas can briefly disagree about the API's health.
