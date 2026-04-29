ALTER TABLE game_snapshots
    ADD COLUMN IF NOT EXISTS current_pitcher_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS current_batter_name VARCHAR(100);
