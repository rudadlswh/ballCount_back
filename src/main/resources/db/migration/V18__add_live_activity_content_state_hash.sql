ALTER TABLE live_activity_tokens
    ADD COLUMN content_state_hash VARCHAR(128),
    ADD COLUMN content_state_json TEXT;

CREATE INDEX idx_live_activity_tokens_content_state_hash ON live_activity_tokens (content_state_hash);
