CREATE TABLE notification_event_keys (
    event_key VARCHAR(255) PRIMARY KEY,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE notification_event_keys ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON notification_event_keys FROM PUBLIC, anon, authenticated;

CREATE INDEX idx_game_events_retention ON game_events (updated_at, id);
CREATE INDEX idx_notification_outbox_retention ON notification_outbox (expires_at, event_id)
    WHERE status IN ('done', 'expired');
CREATE INDEX idx_crawl_jobs_retention ON crawl_jobs (finished_at, id)
    WHERE status IN ('succeeded', 'partial_success', 'failed');
