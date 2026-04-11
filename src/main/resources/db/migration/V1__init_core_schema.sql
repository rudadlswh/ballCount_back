CREATE TABLE teams (
    id UUID PRIMARY KEY,
    team_code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    short_name VARCHAR(50) NOT NULL,
    english_name VARCHAR(100),
    logo_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE games (
    id UUID PRIMARY KEY,
    public_game_id VARCHAR(50) NOT NULL UNIQUE,
    provider VARCHAR(30) NOT NULL,
    provider_game_id VARCHAR(100) NOT NULL,
    game_date DATE NOT NULL,
    scheduled_at TIMESTAMPTZ,
    stadium VARCHAR(100),
    status VARCHAR(30) NOT NULL DEFAULT 'unknown',
    home_team_id UUID NOT NULL,
    away_team_id UUID NOT NULL,
    home_score INTEGER,
    away_score INTEGER,
    inning_state VARCHAR(50),
    is_cancelled BOOLEAN NOT NULL DEFAULT false,
    is_postponed BOOLEAN NOT NULL DEFAULT false,
    source_updated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_games_provider_game UNIQUE (provider, provider_game_id),
    CONSTRAINT fk_games_home_team FOREIGN KEY (home_team_id) REFERENCES teams (id),
    CONSTRAINT fk_games_away_team FOREIGN KEY (away_team_id) REFERENCES teams (id)
);

CREATE TABLE game_snapshots (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL,
    inning INTEGER,
    inning_half VARCHAR(10),
    inning_label VARCHAR(50),
    balls INTEGER,
    strikes INTEGER,
    outs INTEGER,
    runner_on_first BOOLEAN NOT NULL DEFAULT false,
    runner_on_second BOOLEAN NOT NULL DEFAULT false,
    runner_on_third BOOLEAN NOT NULL DEFAULT false,
    home_score INTEGER,
    away_score INTEGER,
    home_hits INTEGER,
    away_hits INTEGER,
    home_errors INTEGER,
    away_errors INTEGER,
    home_balls INTEGER,
    away_balls INTEGER,
    raw_hash VARCHAR(128),
    source_updated_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_game_snapshots_game FOREIGN KEY (game_id) REFERENCES games (id)
);

CREATE TABLE line_scores (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL,
    inning_number INTEGER NOT NULL,
    away_runs INTEGER,
    home_runs INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_line_scores_game_inning UNIQUE (game_id, inning_number),
    CONSTRAINT fk_line_scores_game FOREIGN KEY (game_id) REFERENCES games (id)
);

CREATE TABLE game_events (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL,
    provider_event_id VARCHAR(100),
    sequence_number INTEGER NOT NULL,
    inning INTEGER,
    inning_half VARCHAR(10),
    event_type VARCHAR(50),
    event_text TEXT NOT NULL,
    occurred_at TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_game_events_game_sequence UNIQUE (game_id, sequence_number),
    CONSTRAINT fk_game_events_game FOREIGN KEY (game_id) REFERENCES games (id)
);

CREATE TABLE crawl_jobs (
    id UUID PRIMARY KEY,
    job_type VARCHAR(50) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_key VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    scheduled_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE crawl_failures (
    id UUID PRIMARY KEY,
    crawl_job_id UUID,
    provider VARCHAR(30),
    target_type VARCHAR(50),
    target_key VARCHAR(100),
    failure_stage VARCHAR(50) NOT NULL,
    error_code VARCHAR(100),
    error_message TEXT NOT NULL,
    stack_trace TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_crawl_failures_job FOREIGN KEY (crawl_job_id) REFERENCES crawl_jobs (id)
);

CREATE TABLE crawl_raw_archive (
    id UUID PRIMARY KEY,
    provider VARCHAR(30) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    target_key VARCHAR(100),
    source_url TEXT NOT NULL,
    http_status INTEGER,
    content_type VARCHAR(100),
    body_hash VARCHAR(128),
    body_text TEXT,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_games_game_date ON games (game_date);
CREATE INDEX idx_games_status ON games (status);
CREATE INDEX idx_games_public_game_id ON games (public_game_id);
CREATE INDEX idx_games_home_team_id ON games (home_team_id);
CREATE INDEX idx_games_away_team_id ON games (away_team_id);
CREATE INDEX idx_games_scheduled_at ON games (scheduled_at);
CREATE INDEX idx_games_provider_game_id ON games (provider, provider_game_id);

CREATE INDEX idx_game_snapshots_game_fetched_at ON game_snapshots (game_id, fetched_at DESC);
CREATE INDEX idx_game_snapshots_game_source_updated_at ON game_snapshots (game_id, source_updated_at DESC);

CREATE INDEX idx_line_scores_game_inning_number ON line_scores (game_id, inning_number);

CREATE INDEX idx_game_events_game_sequence_number ON game_events (game_id, sequence_number);
CREATE INDEX idx_game_events_game_occurred_at ON game_events (game_id, occurred_at);

CREATE INDEX idx_crawl_jobs_status ON crawl_jobs (status);
CREATE INDEX idx_crawl_jobs_job_target ON crawl_jobs (job_type, target_type, target_key);
CREATE INDEX idx_crawl_jobs_scheduled_at ON crawl_jobs (scheduled_at);

CREATE INDEX idx_crawl_failures_crawl_job_id ON crawl_failures (crawl_job_id);
CREATE INDEX idx_crawl_failures_occurred_at ON crawl_failures (occurred_at DESC);

CREATE INDEX idx_crawl_raw_archive_provider_resource_target ON crawl_raw_archive (provider, resource_type, target_key);
CREATE INDEX idx_crawl_raw_archive_fetched_at ON crawl_raw_archive (fetched_at DESC);
