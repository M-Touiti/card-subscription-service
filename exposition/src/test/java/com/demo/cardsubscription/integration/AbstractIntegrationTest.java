package com.demo.cardsubscription.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests.
 *
 * Starts two real services via Testcontainers:
 *   - PostgreSQL  (auto-wires spring.datasource.*)
 *   - Kafka       (auto-wires spring.kafka.bootstrap-servers)
 *
 * Containers are started once per JVM run via static initialiser (singleton pattern)
 * so the same ports are used by all subclasses. This allows Spring to cache a single
 * application context across test classes.
 *
 * The external card API (CardApiPort) is mocked with @MockBean in each subclass.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.h2.console.enabled=false"
)
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;
    static final KafkaContainer KAFKA;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
        POSTGRES.start();

        KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
        KAFKA.start();
    }

    @DynamicPropertySource
    static void overrideConnectionProperties(DynamicPropertyRegistry registry) {
        // Datasource — override H2 defaults from application.yml with the real Postgres container
        registry.add("spring.datasource.url",               POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",           POSTGRES::getUsername);
        registry.add("spring.datasource.password",           POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Spring Boot 3.2.x: @ServiceConnection wires the producer but not the consumer.
        // Explicit override ensures the Kafka listener container factory also picks up
        // the Testcontainers broker address.
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
