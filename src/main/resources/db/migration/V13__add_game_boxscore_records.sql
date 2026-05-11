CREATE TABLE IF NOT EXISTS game_batter_records (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL,
    team_id UUID NOT NULL,
    source_order INTEGER NOT NULL,
    batting_order INTEGER,
    position VARCHAR(50),
    player_name VARCHAR(100) NOT NULL,
    at_bats INTEGER,
    runs INTEGER,
    hits INTEGER,
    rbi INTEGER,
    home_runs INTEGER,
    walks INTEGER,
    strikeouts INTEGER,
    stolen_bases INTEGER,
    batting_average VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_game_batter_records_game_team_order UNIQUE (game_id, team_id, source_order),
    CONSTRAINT fk_game_batter_records_game FOREIGN KEY (game_id) REFERENCES games (id),
    CONSTRAINT fk_game_batter_records_team FOREIGN KEY (team_id) REFERENCES teams (id)
);

CREATE TABLE IF NOT EXISTS game_pitcher_records (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL,
    team_id UUID NOT NULL,
    source_order INTEGER NOT NULL,
    pitching_order INTEGER,
    player_name VARCHAR(100) NOT NULL,
    appearance VARCHAR(50),
    decision_result VARCHAR(50),
    wins INTEGER,
    losses INTEGER,
    saves INTEGER,
    innings_pitched VARCHAR(20),
    batters_faced INTEGER,
    pitch_count INTEGER,
    at_bats INTEGER,
    hits INTEGER,
    home_runs INTEGER,
    walks_or_hit_by_pitch INTEGER,
    strikeouts INTEGER,
    runs INTEGER,
    earned_runs INTEGER,
    era VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_game_pitcher_records_game_team_order UNIQUE (game_id, team_id, source_order),
    CONSTRAINT fk_game_pitcher_records_game FOREIGN KEY (game_id) REFERENCES games (id),
    CONSTRAINT fk_game_pitcher_records_team FOREIGN KEY (team_id) REFERENCES teams (id)
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'game_batter_records'::regclass
          AND conname = 'uk_game_batter_records_game_team_order'
    ) THEN
        ALTER TABLE game_batter_records
            ADD CONSTRAINT uk_game_batter_records_game_team_order UNIQUE (game_id, team_id, source_order);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'game_batter_records'::regclass
          AND conname = 'fk_game_batter_records_game'
    ) THEN
        ALTER TABLE game_batter_records
            ADD CONSTRAINT fk_game_batter_records_game FOREIGN KEY (game_id) REFERENCES games (id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'game_batter_records'::regclass
          AND conname = 'fk_game_batter_records_team'
    ) THEN
        ALTER TABLE game_batter_records
            ADD CONSTRAINT fk_game_batter_records_team FOREIGN KEY (team_id) REFERENCES teams (id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'game_pitcher_records'::regclass
          AND conname = 'uk_game_pitcher_records_game_team_order'
    ) THEN
        ALTER TABLE game_pitcher_records
            ADD CONSTRAINT uk_game_pitcher_records_game_team_order UNIQUE (game_id, team_id, source_order);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'game_pitcher_records'::regclass
          AND conname = 'fk_game_pitcher_records_game'
    ) THEN
        ALTER TABLE game_pitcher_records
            ADD CONSTRAINT fk_game_pitcher_records_game FOREIGN KEY (game_id) REFERENCES games (id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'game_pitcher_records'::regclass
          AND conname = 'fk_game_pitcher_records_team'
    ) THEN
        ALTER TABLE game_pitcher_records
            ADD CONSTRAINT fk_game_pitcher_records_team FOREIGN KEY (team_id) REFERENCES teams (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_game_batter_records_game_id ON game_batter_records (game_id);
CREATE INDEX IF NOT EXISTS idx_game_batter_records_team_id ON game_batter_records (team_id);
CREATE INDEX IF NOT EXISTS idx_game_batter_records_game_team_order ON game_batter_records (game_id, team_id, source_order);

CREATE INDEX IF NOT EXISTS idx_game_pitcher_records_game_id ON game_pitcher_records (game_id);
CREATE INDEX IF NOT EXISTS idx_game_pitcher_records_team_id ON game_pitcher_records (team_id);
CREATE INDEX IF NOT EXISTS idx_game_pitcher_records_game_team_order ON game_pitcher_records (game_id, team_id, source_order);
