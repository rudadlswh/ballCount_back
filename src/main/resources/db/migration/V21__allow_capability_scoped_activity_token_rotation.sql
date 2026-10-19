-- A public activity ID cannot authorize replacing a different APNs token.
ALTER TABLE kbo_crawler_api.live_activity_tokens
    DROP CONSTRAINT IF EXISTS uk_live_activity_tokens_activity_id;

CREATE INDEX IF NOT EXISTS idx_live_activity_tokens_activity_id
    ON kbo_crawler_api.live_activity_tokens (activity_id);
