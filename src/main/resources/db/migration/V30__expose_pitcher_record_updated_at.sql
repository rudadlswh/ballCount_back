CREATE OR REPLACE VIEW public_game_pitcher_records AS
SELECT
    id,
    game_id,
    team_id,
    source_order,
    pitching_order,
    player_name,
    appearance,
    decision_result,
    wins,
    losses,
    saves,
    innings_pitched,
    batters_faced,
    pitch_count,
    at_bats,
    hits,
    home_runs,
    walks_or_hit_by_pitch,
    strikeouts,
    runs,
    earned_runs,
    era,
    created_at,
    updated_at
FROM game_pitcher_records;

ALTER VIEW public_game_pitcher_records SET (security_invoker = true);

GRANT SELECT ON public_game_pitcher_records TO anon, authenticated;