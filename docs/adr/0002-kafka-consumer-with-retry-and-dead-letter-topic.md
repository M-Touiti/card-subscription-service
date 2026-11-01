# ADR 0002: Isolated Kafka consumer with retry and a Dead Letter Topic

**Status:** Accepted
**Date:** 2026-05-13, revised 2026-11-01 after load testing

## Context

Card events arrive on `card.events` from an upstream producer whose schema and data quality this service does not control. A transient failure should not lose an event, and one bad event should not block those behind it. The rest of the application should not need to know about Kafka.

[Load tests](../../performance/README.md#findings) of the first version showed that a malformed message stopped its partition indefinitely (F2), and that an auto-created topic with one partition and serial consumption limited a replica to about 14 events/s (F8).

## Decision

- Keep all messaging code in the `kafka-consumer` module. `CardEventMessage` maps to `SubscribeCardCommand` as an anti-corruption layer, and unknown JSON fields are ignored so upstream additions do not break consumption.
- Commit offsets through the listener container, which gives at-least-once delivery. The atomic claim in [ADR 0003](0003-claim-the-card-then-call-the-external-api-outside-a-transaction.md) absorbs redeliveries.
- Use `DefaultErrorHandler` with exponential back-off of 1 s, 2 s and 4 s. Exhausted records go to `card.events.DLT` through `DeadLetterPublishingRecoverer`.
- Wrap the JSON deserializer in `ErrorHandlingDeserializer`, so a malformed record goes straight to the DLT with its original bytes.
- Create both topics at startup with 12 partitions, and run 4 listener threads per replica, so 3 replicas give one thread per partition.
- Leave external API failures out of this path. They become `FAILED` rows for the retry job, so a long outage does not fill the DLT or pause partitions.

Spring Kafka's non-blocking retry topics were the main alternative. Blocking retries were preferred for needing no extra topics and preserving per-partition order, accepting that a partition pauses during back-off.

## Consequences

**Benefits**

- The messaging technology could be replaced by changing one module.
- Invalid events no longer block anything: blank `cardId` and malformed JSON both reach the DLT, later messages are processed, and the log flood of about 56 MB/s is gone.
- One replica went from 14.4 to 64.9 events/s, and 3 replicas reached 175 events/s (2.70x), with one row per event across 72,000 events.

**Costs**

- **Parallelism has to fit the partition count.** A backlog drains at the pace of the thread with the most partitions. Two replicas gain only 1.47x, and 3 threads per replica made a third replica useless in testing. Scaling past 3 replicas needs more partitions.
- **Order holds per partition only,** so producers must key events by card if order matters for a card.
- **Each listener thread is still sequential,** at about 17 events/s with a 50 ms API, because it waits for the external call.
- **The DLT has no consumer.** Records there wait for someone to inspect and replay them.
