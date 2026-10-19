ALTER TABLE team_ranks
    ADD COLUMN IF NOT EXISTS previous_rank INTEGER;

COMMENT ON COLUMN team_ranks.previous_rank IS
    'Rank calculated from completed regular-season games before the latest completed game day.';
