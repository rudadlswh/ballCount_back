CREATE TABLE live_activity_push_to_start_tokens (
    id UUID PRIMARY KEY,
    platform VARCHAR(20) NOT NULL,
    environment VARCHAR(30) NOT NULL,
    push_to_start_token TEXT NOT NULL,
    installation_id VARCHAR(100) NOT NULL,
    favorite_team_id VARCHAR(30),
    notifications_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    live_activities_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    live_activity_auto_start_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    game_start_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    favorite_team_only_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_started_game_key VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_live_activity_pts_platform_env_token UNIQUE (platform, environment, push_to_start_token),
    CONSTRAINT uk_live_activity_pts_platform_env_installation UNIQUE (platform, environment, installation_id)
);

CREATE INDEX idx_live_activity_pts_active_env
    ON live_activity_push_to_start_tokens (active, environment);

CREATE INDEX idx_live_activity_pts_favorite_team
    ON live_activity_push_to_start_tokens (favorite_team_id);
