ALTER TABLE games
ADD COLUMN home_starting_pitcher_name VARCHAR(100),
ADD COLUMN away_starting_pitcher_name VARCHAR(100),
ADD COLUMN lineup_data JSONB,
ADD COLUMN status_reason TEXT,
ADD COLUMN final_confirmed_at TIMESTAMPTZ,
ADD COLUMN live_last_checked_at TIMESTAMPTZ;

CREATE INDEX idx_games_live_last_checked_at ON games (live_last_checked_at);
CREATE INDEX idx_games_final_confirmed_at ON games (final_confirmed_at);

CREATE TABLE notification_devices (
    id UUID PRIMARY KEY,
    platform VARCHAR(20) NOT NULL,
    device_token TEXT NOT NULL,
    installation_id VARCHAR(100),
    favorite_team_id VARCHAR(30),
    notifications_enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_notification_devices_platform_token UNIQUE (platform, device_token)
);

CREATE INDEX idx_notification_devices_installation_id ON notification_devices (installation_id);
CREATE INDEX idx_notification_devices_favorite_team_id ON notification_devices (favorite_team_id);

CREATE TABLE notification_events (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_key VARCHAR(255) NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    delivery_status VARCHAR(30) NOT NULL DEFAULT 'pending',
    error_message TEXT,
    CONSTRAINT uk_notification_events_event_key UNIQUE (event_key),
    CONSTRAINT fk_notification_events_game FOREIGN KEY (game_id) REFERENCES games (id)
);

CREATE INDEX idx_notification_events_game_id ON notification_events (game_id);
CREATE INDEX idx_notification_events_created_at ON notification_events (created_at DESC);
