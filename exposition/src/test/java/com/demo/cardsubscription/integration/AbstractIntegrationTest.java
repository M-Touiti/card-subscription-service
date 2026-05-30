package com.demo.cardsubscription.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests.
 *
 * Starts two real services via Testcontainers:
 *   - PostgreSQL  (@ServiceConnection auto-wires spring.datasource.*)
 *   - Kafka       (@ServiceConnection auto-wires spring.kafka.bootstrap-servers)
 *
 * The external card API (CardApiPort) is mocked with @MockBean in each subclass —
 * integration tests verify the service/persistence/Kafka flow across the port boundary,
 * not the HTTP adapter wiring (which has its own unit tests).
 *
 * Containers are static so they start once and are shared across all subclasses.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.h2.console.enabled=false"
)
@Testcontainers
abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
}
