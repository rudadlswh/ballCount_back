-- Keep the audit row, but hide obsolete fixtures from app schedules.
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
FROM ${appSchema}.games
WHERE NOT (status = 'cancelled' AND season_type = 'postseason'
    AND COALESCE(status_reason, '') = '포스트시즌 공식 일정 변경으로 기존 일정 제외');

