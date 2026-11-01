-- Earlier versions could store several rows per card. Keep one: a SUBSCRIBED row
-- if there is one, otherwise the most recent.
DELETE FROM card_subscription
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY card_id
                   ORDER BY CASE WHEN status = 'SUBSCRIBED' THEN 0 ELSE 1 END, requested_at DESC, id
               ) AS row_position
        FROM card_subscription
    ) ranked
    WHERE row_position > 1
);

ALTER TABLE card_subscription ADD CONSTRAINT uk_card_subscription_card_id UNIQUE (card_id);

ALTER TABLE card_subscription ADD COLUMN attempts INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE card_subscription ADD COLUMN next_retry_at TIMESTAMP(6);

CREATE INDEX idx_card_subscription_next_retry_at ON card_subscription (next_retry_at);

-- Rows that were already FAILED stay as they are until requested again, so that deploying
-- this version does not suddenly re-send old subscriptions. A leftover PENDING row cannot
-- be in progress, so it is due now.
UPDATE card_subscription SET next_retry_at = CURRENT_TIMESTAMP WHERE status = 'PENDING';
