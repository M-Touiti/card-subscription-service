# Architecture Decision Records

Each record captures one significant decision: the context, what was decided, and what it costs. These were written retrospectively from the code, commit history and [load-test results](../../performance/README.md). Each date is that of the commit that introduced the decision, followed by the revision made after load testing, if any.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-hexagonal-architecture-with-maven-modules.md) | Hexagonal architecture enforced by Maven modules | Accepted |
| [0002](0002-kafka-consumer-with-retry-and-dead-letter-topic.md) | Isolated Kafka consumer with retry and a Dead Letter Topic | Accepted |
| [0003](0003-claim-the-card-then-call-the-external-api-outside-a-transaction.md) | Claim the card, then call the external API outside a transaction | Accepted |
| [0004](0004-circuit-breaker-with-failed-status-fallback.md) | Circuit breaker that records failures as `FAILED` | Accepted |
| [0005](0005-integration-tests-with-testcontainers-and-mocked-card-api.md) | Integration tests on real infrastructure with a mocked card API | Accepted |

New decisions take the next number. To change a decision, add a new ADR that supersedes the old one rather than rewriting it.
