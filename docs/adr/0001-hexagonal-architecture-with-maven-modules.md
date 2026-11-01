# ADR 0001: Hexagonal architecture enforced by Maven modules

**Status:** Accepted
**Date:** 2026-05-13

## Context

The service has two entry points, REST and Kafka, that must run the same subscription logic. It also depends on two external systems, a database and a third-party card API. Business rules need to stay testable without any of them, and either side should be replaceable without touching those rules.

Package conventions alone would not protect this structure, because nothing stops a class importing across layers. Separate deployable services would be excessive for one bounded context.

## Decision

Use Ports and Adapters in a single deployable, with each layer as a Maven module:

```
exposition ──► kafka-consumer ──┐
          └──► infrastructure ──┴──► application ──► domain
```

- `domain` has no dependencies at all: no Spring, JPA or Lombok. `CardSubscription` uses a hand-written builder.
- `application` defines inbound use cases and outbound ports, and contains `CardSubscriptionService`. It allows `spring-context` and `spring-tx` so it can use `@Service` and `@Transactional`.
- `infrastructure` and `kafka-consumer` are adapters that depend inward only.
- `exposition` holds the REST API and the Spring Boot entry point, and assembles the modules.

## Consequences

**Benefits**

- Dependency direction is enforced at compile time: `domain` cannot import Spring because it is not on its classpath.
- REST and Kafka share one use case, so their behaviour cannot drift apart.
- Service tests run against mocked ports with no Spring context.

**Costs**

- More code for a service this size: extra interfaces, mappers and a hand-written builder.
- Dependency rules are not checked *within* a module. Nothing prevents a controller from bypassing a converter, for example, and no ArchUnit test exists yet.
- `application` is not framework-free. Declaring the transaction boundary there was pragmatic, but it also made it easy to place an external HTTP call inside a transaction, a mistake the load tests later found ([ADR 0003](0003-claim-the-card-then-call-the-external-api-outside-a-transaction.md)).
- All modules still deploy and scale as one unit, so REST and Kafka load cannot be scaled independently.
