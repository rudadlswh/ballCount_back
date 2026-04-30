DROP VIEW IF EXISTS public_latest_game_snapshots;

CREATE VIEW public_latest_game_snapshots AS
SELECT DISTINCT ON (snapshot.game_id)
    snapshot.game_id,
    snapshot.inning_label,
    snapshot.balls,
    snapshot.strikes,
    snapshot.outs,
    snapshot.runner_on_first,
    snapshot.runner_on_second,
    snapshot.runner_on_third,
    snapshot.current_pitcher_name,
    snapshot.current_batter_name
FROM game_snapshots snapshot
ORDER BY
    snapshot.game_id,
    COALESCE(snapshot.source_updated_at, snapshot.fetched_at) DESC,
    snapshot.fetched_at DESC,
    snapshot.created_at DESC;
