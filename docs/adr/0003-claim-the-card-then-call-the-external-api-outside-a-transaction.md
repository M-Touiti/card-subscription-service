# ADR 0003: Claim the card, then call the external API outside a transaction

**Status:** Accepted
**Date:** 2026-05-13, revised 2026-11-01 after load testing

## Context

Subscribing a card requires a call to a third-party API. Every attempt should leave a record, REST callers expect a result in the response, and repeated requests must not subscribe a card twice, even across replicas. REST and Kafka both use this flow.

The original design ran the lookup, `PENDING` insert, external call and final update in one `@Transactional` method. [Load tests](../../performance/README.md#findings) showed three problems: 5 parallel requests for each of 200 cards created 991 rows (F1), the held connection capped writes at `pool / latency`, or 44 req/s at 200 ms (F3), and at one million rows an unindexed lookup limited writes to 11.5 req/s (F4).

## Decision

Keep one synchronous flow, because an outbox or worker would give up the immediate result and add infrastructure, but split it so each step commits on its own:

1. **Claim.** A unique constraint on `card_id` plus `INSERT ... ON CONFLICT DO NOTHING` for a new card, or a conditional `UPDATE` from `FAILED` to `PENDING` for a retry. Only one caller wins; the others return the current state.
2. **Call** the external API with no transaction open.
3. **Update** the same row to `SUBSCRIBED`, or to `FAILED` with a `next_retry_at`.

`SubscriptionRetryJob` retries due rows every 5 s, with delays doubling from 30 s to 30 min and at most 8 attempts. A `PENDING` row carries a 60 s lease in the same column, so a process that dies mid-call does not strand its card. Flyway owns the schema, including a migration that removes existing duplicates.

## Consequences

**Benefits**

- One row per card: 200 rows and no duplicates in the same race test, and all 20 cards retried after an outage became `SUBSCRIBED`.
- External latency no longer sets capacity. At pool 10 and 200 ms, writes went from 44 to 510 req/s, and CPU is now the limit.
- A 40 s outage clears without intervention: every row was `SUBSCRIBED` 283 s after the error test ended and 394 s after the hang test ended.
- The unique constraint also indexes `card_id`: subscribe reached 338 req/s at one million rows, a figure limited by the 20 test users.

**Costs**

- **Calls are at-least-once.** If a call times out after the partner acted on it, the retry repeats it, so the partner must deduplicate by `cardId`.
- **The result is not always final.** A caller that loses the claim gets `201` with `PENDING`, and failures also return `201`, so clients must read `status`.
- **Recovery is paced.** One replica retries at most 50 rows per 5 s. Calls rejected by an open breaker still use attempts, so an outage longer than about an hour leaves rows `FAILED` until the card is requested again.
- **Upgrading deletes data.** The migration keeps one row per card and deletes the rest. Back up first.
