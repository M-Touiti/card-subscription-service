# ADR 0005: Integration tests on real infrastructure with a mocked card API

**Status:** Accepted
**Date:** 2026-05-30, revised 2026-11-01 after load testing

## Context

Unit tests with mocked ports cannot show that JPA mappings, SQL, Kafka deserialization and Spring wiring work together. Local development uses H2, which differs from production PostgreSQL even in compatibility mode, and an embedded broker differs from real Kafka. The third-party card API has no test environment available to this project.

The first suite passed despite the duplicate-row and malformed-message defects later found by the [load tests](../../performance/README.md#findings) (F1, F2). Its integration tests also did not run in `mvn test` or CI, because Surefire's default patterns skip `*IT` classes.

## Decision

- Run integration tests with `@SpringBootTest` against real `postgres:16-alpine` and `confluentinc/cp-kafka:7.6.0` containers, using Testcontainers.
- Include `*IT` classes in Surefire, so `mvn test` and CI run them.
- Start the containers once per JVM with the singleton pattern and pass their addresses through `@DynamicPropertySource`, so all test classes share one cached Spring context. `@ServiceConnection` was dropped because, on Spring Boot 3.2.x, it left the Kafka consumer pointing at `localhost:9092`.
- Replace `CardApiPort` with `@MockBean`, so tests control the external result without network calls.
- Turn each confirmed defect into a regression test: concurrent requests produce one row, a retry reuses the row, no transaction is open during the external call, a malformed message reaches the DLT and the partition continues, and the migration handles existing duplicates.
- Run the claim queries on H2 as well (`@DataJpaTest`), because local development uses it.

## Consequences

**Benefits**

- Tests exercise the real database and broker, with no manual `docker compose` step.
- Failure scenarios are simple to set up by changing the mock's response.
- The new tests detect the defects: restoring the class-level `@Transactional` or the plain JSON deserializer makes them fail.

**Costs**

- `mvn test` and `mvn install` now require Docker. On Windows with Docker Desktop, that also needs the TCP daemon endpoint, a user-level `.testcontainers.properties` and `api.version=1.41` in Surefire.
- Mocking at the port leaves `ExternalCardApiAdapter`, its HTTP mapping and the `@CircuitBreaker` annotation untested here. Only the load tests exercise them, against a WireMock stub.
- Load tests are run manually and are not part of CI, so performance regressions are not caught automatically.
