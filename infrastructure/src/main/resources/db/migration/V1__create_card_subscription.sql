-- The schema as Hibernate created it before migrations were introduced.
-- Databases that already have this table are baselined at this version.
CREATE TABLE IF NOT EXISTS card_subscription (
    id            VARCHAR(255) NOT NULL PRIMARY KEY,
    card_id       VARCHAR(255) NOT NULL,
    user_id       VARCHAR(255) NOT NULL,
    status        VARCHAR(255) NOT NULL,
    requested_at  TIMESTAMP(6) NOT NULL,
    processed_at  TIMESTAMP(6),
    error_message VARCHAR(512)
);
