ALTER TABLE game_snapshots
    ADD COLUMN IF NOT EXISTS first_base_runner_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS second_base_runner_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS third_base_runner_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS first_base_runner_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS second_base_runner_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS third_base_runner_id VARCHAR(100);

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
    snapshot.first_base_runner_name,
    snapshot.second_base_runner_name,
    snapshot.third_base_runner_name,
    snapshot.first_base_runner_id,
    snapshot.second_base_runner_id,
    snapshot.third_base_runner_id,
    snapshot.current_pitcher_name,
    snapshot.current_batter_name
FROM game_snapshots snapshot
ORDER BY
    snapshot.game_id,
    COALESCE(snapshot.source_updated_at, snapshot.fetched_at) DESC,
    snapshot.fetched_at DESC,
    snapshot.created_at DESC;
