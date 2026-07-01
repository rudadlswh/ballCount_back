CREATE INDEX IF NOT EXISTS idx_game_snapshots_game_created_at_desc
    ON kbo_crawler_api.game_snapshots (game_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_games_public_game_id
    ON kbo_crawler_api.games (public_game_id);

CREATE INDEX IF NOT EXISTS idx_notification_devices_delivery_target
    ON kbo_crawler_api.notification_devices (environment, platform, notifications_enabled, favorite_team_id);

CREATE UNIQUE INDEX IF NOT EXISTS ux_notification_events_event_key
    ON kbo_crawler_api.notification_events (event_key);
