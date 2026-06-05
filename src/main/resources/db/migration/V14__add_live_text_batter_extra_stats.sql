ALTER TABLE kbo_crawler_api.game_batter_records
    ADD COLUMN IF NOT EXISTS grounded_into_double_play INTEGER,
    ADD COLUMN IF NOT EXISTS errors INTEGER;

CREATE OR REPLACE VIEW public_game_batter_records AS
SELECT
    id,
    game_id,
    team_id,
    source_order,
    batting_order,
    position,
    player_name,
    at_bats,
    runs,
    hits,
    rbi,
    home_runs,
    walks,
    strikeouts,
    stolen_bases,
    grounded_into_double_play,
    errors,
    batting_average,
    created_at,
    updated_at
FROM kbo_crawler_api.game_batter_records;

GRANT SELECT ON public_game_batter_records TO anon, authenticated;
