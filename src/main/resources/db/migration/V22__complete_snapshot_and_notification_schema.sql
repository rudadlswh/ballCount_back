ALTER TABLE kbo_crawler_api.game_snapshots
    ADD COLUMN IF NOT EXISTS last_completed_batter_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS last_completed_inning VARCHAR(255),
    ADD COLUMN IF NOT EXISTS last_completed_pitcher_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS last_completed_play_result VARCHAR(255),
    ADD COLUMN IF NOT EXISTS last_completed_play_key VARCHAR(255);

ALTER TABLE kbo_crawler_api.notification_devices
    ADD COLUMN IF NOT EXISTS game_start_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS score_change_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS lead_change_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS game_end_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS on_base_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS inning_change_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS favorite_team_only_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS mute_when_losing_enabled BOOLEAN NOT NULL DEFAULT FALSE;
