CREATE TABLE IF NOT EXISTS team_ranks (
    season INTEGER NOT NULL,
    team_id UUID NOT NULL REFERENCES teams (id),
    rank INTEGER,
    team_name VARCHAR,
    games_played INTEGER,
    wins INTEGER,
    losses INTEGER,
    draws INTEGER,
    winning_percentage NUMERIC(5,3),
    games_behind NUMERIC(4,1),
    streak_type VARCHAR,
    streak_count INTEGER,
    streak_text VARCHAR,
    last_game_date DATE,
    calculated_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT team_ranks_pkey PRIMARY KEY (season, team_id)
);

CREATE INDEX IF NOT EXISTS team_ranks_season_idx ON team_ranks (season);
CREATE INDEX IF NOT EXISTS team_ranks_season_rank_idx ON team_ranks (season, rank);

ALTER TABLE team_ranks ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_policies
        WHERE schemaname = 'kbo_crawler_api'
          AND tablename = 'team_ranks'
          AND policyname = 'team_ranks_select_readonly'
    ) THEN
        CREATE POLICY team_ranks_select_readonly
            ON team_ranks
            FOR SELECT
            TO anon, authenticated
            USING (true);
    END IF;
END $$;

GRANT USAGE ON SCHEMA kbo_crawler_api TO anon, authenticated;
REVOKE INSERT, UPDATE, DELETE ON TABLE team_ranks FROM anon, authenticated;
GRANT SELECT ON TABLE team_ranks TO anon, authenticated;

CREATE OR REPLACE VIEW team_rank_2026
WITH (security_invoker = true)
AS
SELECT
    season,
    team_id,
    rank,
    team_name,
    games_played,
    wins,
    losses,
    draws,
    winning_percentage,
    games_behind,
    streak_type,
    streak_count,
    streak_text,
    last_game_date,
    calculated_at,
    updated_at,
    created_at
FROM team_ranks
WHERE season = 2026;

REVOKE INSERT, UPDATE, DELETE ON TABLE team_rank_2026 FROM anon, authenticated;
GRANT SELECT ON TABLE team_rank_2026 TO anon, authenticated;
