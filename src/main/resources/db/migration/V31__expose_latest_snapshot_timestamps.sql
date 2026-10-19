CREATE OR REPLACE VIEW kbo_crawler_api.public_latest_game_snapshots AS
SELECT DISTINCT ON (snapshot.game_id)
    snapshot.game_id,
    snapshot.inning_label,
    snapshot.balls,
    snapshot.strikes,
    snapshot.outs,
    snapshot.runner_on_first,
    snapshot.runner_on_second,
    snapshot.runner_on_third,
    snapshot.first_base_runner_name,
    snapshot.second_base_runner_name,
    snapshot.third_base_runner_name,
    snapshot.first_base_runner_id,
    snapshot.second_base_runner_id,
    snapshot.third_base_runner_id,
    snapshot.current_pitcher_name,
    snapshot.current_batter_name,
    snapshot.home_score,
    snapshot.away_score,
    snapshot.source_updated_at,
    snapshot.fetched_at,
    snapshot.created_at,
    COALESCE(snapshot.source_updated_at, snapshot.fetched_at, snapshot.created_at) AS updated_at
FROM kbo_crawler_api.game_snapshots snapshot
ORDER BY
    snapshot.game_id,
    COALESCE(snapshot.source_updated_at, snapshot.fetched_at) DESC,
    snapshot.fetched_at DESC,
    snapshot.created_at DESC;

ALTER VIEW kbo_crawler_api.public_latest_game_snapshots SET (security_invoker = false);

COMMENT ON VIEW kbo_crawler_api.public_latest_game_snapshots IS 'Public app read projection for latest live snapshot state, including runner names, score, and snapshot timestamps.';

GRANT SELECT ON TABLE kbo_crawler_api.public_latest_game_snapshots TO anon, authenticated;
