ALTER TABLE kbo_crawler_api.game_batter_records
    ADD COLUMN IF NOT EXISTS grounded_into_double_play integer,
    ADD COLUMN IF NOT EXISTS errors integer;

CREATE OR REPLACE VIEW kbo_crawler_api.public_game_batter_records AS
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
    batting_average,
    created_at,
    updated_at,
    grounded_into_double_play,
    errors
FROM kbo_crawler_api.game_batter_records;