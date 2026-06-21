CREATE OR REPLACE VIEW kbo_crawler_api.public_teams AS
SELECT
    id,
    team_code,
    name,
    short_name
FROM kbo_crawler_api.teams;

CREATE OR REPLACE VIEW kbo_crawler_api.public_games AS
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
    home_starting_pitcher_name
FROM kbo_crawler_api.games;

CREATE OR REPLACE VIEW kbo_crawler_api.public_game_events AS
SELECT
    id,
    game_id,
    provider_event_id,
    sequence_number,
    inning,
    inning_half,
    event_type,
    event_text
FROM kbo_crawler_api.game_events;

ALTER VIEW kbo_crawler_api.public_teams SET (security_invoker = false);
ALTER VIEW kbo_crawler_api.public_games SET (security_invoker = false);
ALTER VIEW kbo_crawler_api.public_game_events SET (security_invoker = false);

COMMENT ON VIEW kbo_crawler_api.public_teams IS 'Public app read projection. Security-definer view boundary; definition must not expose sensitive columns.';
COMMENT ON VIEW kbo_crawler_api.public_games IS 'Public app read projection. Security-definer view boundary; definition must not expose sensitive columns.';
COMMENT ON VIEW kbo_crawler_api.public_game_events IS 'Public app read projection. Security-definer view boundary; definition must not expose sensitive columns.';

GRANT SELECT ON TABLE kbo_crawler_api.public_teams TO anon, authenticated;
GRANT SELECT ON TABLE kbo_crawler_api.public_games TO anon, authenticated;
GRANT SELECT ON TABLE kbo_crawler_api.public_game_events TO anon, authenticated;

REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.teams FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.games FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.game_events FROM anon, authenticated;
