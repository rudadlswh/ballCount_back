GRANT USAGE ON SCHEMA kbo_crawler_api TO anon, authenticated;

ALTER TABLE kbo_crawler_api.teams ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.games ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.game_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.line_scores ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.game_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.game_batter_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.game_pitcher_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.team_ranks ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.notification_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.notification_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.live_activity_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.crawl_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.crawl_failures ENABLE ROW LEVEL SECURITY;
ALTER TABLE kbo_crawler_api.crawl_raw_archive ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_policies
        WHERE schemaname = 'kbo_crawler_api'
          AND tablename = 'teams'
          AND policyname = 'teams_public_select'
    ) THEN
        CREATE POLICY teams_public_select
            ON kbo_crawler_api.teams
            FOR SELECT
            TO anon, authenticated
            USING (true);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_policies
        WHERE schemaname = 'kbo_crawler_api'
          AND tablename = 'games'
          AND policyname = 'games_public_select'
    ) THEN
        CREATE POLICY games_public_select
            ON kbo_crawler_api.games
            FOR SELECT
            TO anon, authenticated
            USING (true);
    END IF;
END $$;

DROP POLICY IF EXISTS team_ranks_select_readonly ON kbo_crawler_api.team_ranks;

REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.game_snapshots FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.line_scores FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.game_events FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.game_batter_records FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.game_pitcher_records FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.team_ranks FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.notification_devices FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.notification_events FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.live_activity_tokens FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.crawl_jobs FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.crawl_failures FROM anon, authenticated;
REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.crawl_raw_archive FROM anon, authenticated;

REVOKE INSERT, UPDATE, DELETE ON TABLE kbo_crawler_api.teams FROM anon, authenticated;
REVOKE INSERT, UPDATE, DELETE ON TABLE kbo_crawler_api.games FROM anon, authenticated;
GRANT SELECT ON TABLE kbo_crawler_api.teams TO anon, authenticated;
GRANT SELECT ON TABLE kbo_crawler_api.games TO anon, authenticated;

DO $$
BEGIN
    IF to_regclass('kbo_crawler_api.public_latest_game_snapshots') IS NOT NULL THEN
        EXECUTE 'ALTER VIEW kbo_crawler_api.public_latest_game_snapshots SET (security_invoker = false)';
        EXECUTE 'COMMENT ON VIEW kbo_crawler_api.public_latest_game_snapshots IS ''Public app read projection. Security-definer view boundary; definition must not expose sensitive columns.''';
        EXECUTE 'GRANT SELECT ON kbo_crawler_api.public_latest_game_snapshots TO anon, authenticated';
    END IF;

    IF to_regclass('kbo_crawler_api.public_game_batter_records') IS NOT NULL THEN
        EXECUTE 'ALTER VIEW kbo_crawler_api.public_game_batter_records SET (security_invoker = false)';
        EXECUTE 'COMMENT ON VIEW kbo_crawler_api.public_game_batter_records IS ''Public app read projection. Security-definer view boundary; definition must not expose sensitive columns.''';
        EXECUTE 'GRANT SELECT ON kbo_crawler_api.public_game_batter_records TO anon, authenticated';
    END IF;

    IF to_regclass('kbo_crawler_api.public_game_pitcher_records') IS NOT NULL THEN
        EXECUTE 'ALTER VIEW kbo_crawler_api.public_game_pitcher_records SET (security_invoker = false)';
        EXECUTE 'COMMENT ON VIEW kbo_crawler_api.public_game_pitcher_records IS ''Public app read projection. Security-definer view boundary; definition must not expose sensitive columns.''';
        EXECUTE 'GRANT SELECT ON kbo_crawler_api.public_game_pitcher_records TO anon, authenticated';
    END IF;

    IF to_regclass('kbo_crawler_api.team_rank_2026') IS NOT NULL THEN
        EXECUTE 'ALTER VIEW kbo_crawler_api.team_rank_2026 SET (security_invoker = false)';
        EXECUTE 'COMMENT ON VIEW kbo_crawler_api.team_rank_2026 IS ''Public app read projection. Security-definer view boundary over team_ranks.''';
        EXECUTE 'GRANT SELECT ON kbo_crawler_api.team_rank_2026 TO anon, authenticated';
    END IF;
END $$;
