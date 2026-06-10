UPDATE kbo_crawler_api.live_activity_tokens
SET activity_token = push_token
WHERE activity_token IS NULL
  AND push_token IS NOT NULL;

ALTER TABLE kbo_crawler_api.live_activity_tokens
    ALTER COLUMN push_token DROP NOT NULL;

ALTER TABLE kbo_crawler_api.live_activity_tokens
    ALTER COLUMN activity_token SET NOT NULL;

ALTER TABLE kbo_crawler_api.live_activity_tokens
    DROP CONSTRAINT IF EXISTS uk_live_activity_tokens_platform_env_token;

ALTER TABLE kbo_crawler_api.live_activity_tokens
    ADD CONSTRAINT uk_live_activity_tokens_platform_env_activity_token
    UNIQUE (platform, environment, activity_token);
