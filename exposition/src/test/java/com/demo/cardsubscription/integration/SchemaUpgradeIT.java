package com.demo.cardsubscription.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Upgrades a database as Hibernate left it before migrations existed, containing the
 * duplicate rows that the old code could write. Does not need the application context.
 */
@DisplayName("Schema migration from a pre-Flyway database")
class SchemaUpgradeIT {

    private static final String SCHEMA = "pre_flyway";

    private final String url = AbstractIntegrationTest.POSTGRES.getJdbcUrl() + "&currentSchema=" + SCHEMA;
    private final String user = AbstractIntegrationTest.POSTGRES.getUsername();
    private final String password = AbstractIntegrationTest.POSTGRES.getPassword();

    private void insert(JdbcTemplate jdbc, String id, String cardId, String status, String requestedAt) {
        jdbc.update("INSERT INTO card_subscription (id, card_id, user_id, status, requested_at) "
                + "VALUES (?, ?, 'user', ?, ?::timestamp)", id, cardId, status, requestedAt);
    }

    private String keptId(JdbcTemplate jdbc, String cardId) {
        return jdbc.queryForObject("SELECT id FROM card_subscription WHERE card_id = ?", String.class, cardId);
    }

    @Test
    @DisplayName("Keeps one row per card, preferring SUBSCRIBED, and enforces it from then on")
    void upgrade_deduplicatesAndAddsConstraint() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        jdbc.execute("""
                CREATE TABLE card_subscription (
                    id VARCHAR(255) NOT NULL PRIMARY KEY, card_id VARCHAR(255) NOT NULL,
                    user_id VARCHAR(255) NOT NULL, status VARCHAR(255) NOT NULL,
                    requested_at TIMESTAMP(6) NOT NULL, processed_at TIMESTAMP(6), error_message VARCHAR(512))
                """);
        insert(jdbc, "failed-newer", "card-a", "FAILED", "2026-01-02 00:00:00");
        insert(jdbc, "subscribed-older", "card-a", "SUBSCRIBED", "2026-01-01 00:00:00");
        insert(jdbc, "failed-older", "card-b", "FAILED", "2026-01-01 00:00:00");
        insert(jdbc, "failed-newest", "card-b", "FAILED", "2026-01-02 00:00:00");
        insert(jdbc, "abandoned", "card-c", "PENDING", "2026-01-01 00:00:00");

        Flyway.configure().dataSource(url, user, password).schemas(SCHEMA).baselineOnMigrate(true).load().migrate();

        assertThat(keptId(jdbc, "card-a")).isEqualTo("subscribed-older");
        assertThat(keptId(jdbc, "card-b")).isEqualTo("failed-newest");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_subscription WHERE next_retry_at IS NOT NULL "
                + "AND status = 'PENDING'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_subscription WHERE next_retry_at IS NOT NULL "
                + "AND status <> 'PENDING'", Integer.class)).isZero();
        assertThatThrownBy(() -> insert(jdbc, "duplicate", "card-a", "PENDING", "2026-01-03 00:00:00"))
                .hasMessageContaining("uk_card_subscription_card_id");
    }
}
