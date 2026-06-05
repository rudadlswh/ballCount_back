CREATE TABLE live_activity_tokens (
    id UUID PRIMARY KEY,
    activity_id VARCHAR(120),
    platform VARCHAR(20) NOT NULL,
    environment VARCHAR(30) NOT NULL,
    activity_token TEXT NOT NULL,
    installation_id VARCHAR(100),
    favorite_team_id VARCHAR(30),
    public_game_id VARCHAR(80),
    provider_game_id VARCHAR(100),
    database_id VARCHAR(80),
    stable_detail_identity VARCHAR(180),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_live_activity_tokens_activity_env_identity UNIQUE (
        environment,
        activity_token,
        public_game_id,
        provider_game_id,
        database_id,
        stable_detail_identity
    )
);

CREATE INDEX idx_live_activity_tokens_active ON live_activity_tokens (active);
CREATE INDEX idx_live_activity_tokens_environment_token ON live_activity_tokens (environment, activity_token);
CREATE INDEX idx_live_activity_tokens_public_game_id ON live_activity_tokens (public_game_id);
CREATE INDEX idx_live_activity_tokens_provider_game_id ON live_activity_tokens (provider_game_id);
CREATE INDEX idx_live_activity_tokens_database_id ON live_activity_tokens (database_id);
CREATE INDEX idx_live_activity_tokens_stable_identity ON live_activity_tokens (stable_detail_identity);
