ALTER TABLE kbo_crawler_api.live_activity_tokens
    ADD COLUMN IF NOT EXISTS activity_token TEXT;
