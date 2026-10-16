-- Existing rows stay unknown until an authoritative series-specific schedule is imported.
ALTER TABLE ${appSchema}.games
    ADD COLUMN season_type VARCHAR(30) NOT NULL DEFAULT 'unknown',
    ADD COLUMN series_id INTEGER,
    ADD CONSTRAINT ck_games_season_series CHECK (
        (series_id IS NULL AND season_type = 'unknown') OR
        (series_id IS NOT NULL AND CASE series_id
            WHEN 0 THEN season_type = 'regular_season'
            WHEN 9 THEN season_type = 'exhibition_preseason'
            WHEN 6 THEN season_type = 'all_star'
            WHEN 3 THEN season_type = 'postseason'
            WHEN 4 THEN season_type = 'postseason'
            WHEN 5 THEN season_type = 'postseason'
            WHEN 7 THEN season_type = 'postseason'
            ELSE FALSE END)
    );

COMMENT ON COLUMN ${appSchema}.games.series_id IS
    'Official KBO series: 0 regular, 9 preseason, 6 all-star, 4 wildcard, 3 semi-playoff, 5 playoff, 7 Korean Series.';

CREATE OR REPLACE VIEW ${appSchema}.public_games AS
SELECT
    id,
    public_game_id,
    provider,
    provider_game_id,
    official_provider_game_id,
    game_date,
    scheduled_at,
    stadium,
    status,
    status_reason,
    home_team_id,
    away_team_id,
    home_score,
    away_score,
    inning_state,
    is_cancelled,
    is_postponed,
    source_updated_at,
    updated_at,
    final_confirmed_at,
    live_last_checked_at,
    away_starting_pitcher_name,
    home_starting_pitcher_name,
    season_type,
    series_id
FROM ${appSchema}.games;

COMMENT ON VIEW ${appSchema}.public_games IS
    'Public app game projection including authoritative season_type and series_id; existing access boundary is preserved.';
